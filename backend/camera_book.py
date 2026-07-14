"""Camera-driven chapter music.

The user types a book NAME (no PDF). We ask Gemini to generate a TOC plus
per-chapter mood/music prompts from that name. Lyria captures a short PCM
segment for each chapter. A physical XIAO ESP32 camera (running on a
separate Node server, default http://192.168.0.188:4000) snaps the page
the reader is on; we hand the image to Gemini Vision with the TOC and ask
"which chapter does this photo show?" The matching chapter's PCM segment
becomes the active stream over the /ws/camera/{session_id} WebSocket.

All session state is in-memory — these sessions are ephemeral by design
(camera + live book reading), so we don't persist to SQLite.
"""

import asyncio
import html as html_lib
import io
import json
import os
import re
import ssl
from pathlib import Path
from urllib.parse import quote_plus
from urllib.request import Request, urlopen

from google import genai
from google.genai import types
from PIL import Image, ImageFilter, ImageOps, ImageStat

from audio_cache import BYTES_PER_SECOND, SEGMENT_DURATION_S, capture_lyria_pcm

CAMERA_SERVER_URL = os.environ.get(
    "CAMERA_SERVER_URL", "http://192.168.0.188:4000"
).rstrip("/")

CAMERA_AUDIO_ROOT = Path(__file__).parent / "storage" / "camera_audio"
CAMERA_AUDIO_ROOT.mkdir(parents=True, exist_ok=True)

_client: genai.Client | None = None


def _extract_json(text: str) -> dict:
    """Pull a JSON object out of a model response.

    When tool-use (Google Search grounding) is on we can't ask for
    `response_mime_type=application/json`, so the model may wrap the answer
    in a ```json fence or surround it with a sentence. Strip the fence and
    scan for the outermost {...} block."""
    if not text:
        return {}
    s = text.strip()
    if s.startswith("```"):
        s = s.lstrip("`")
        if s.lower().startswith("json"):
            s = s[4:]
        s = s.split("```", 1)[0].strip()
    try:
        return json.loads(s)
    except Exception:
        pass
    start = s.find("{")
    end = s.rfind("}")
    if start >= 0 and end > start:
        try:
            return json.loads(s[start : end + 1])
        except Exception:
            return {}
    return {}


# Crawler-only SSL context: many corporate / local networks MITM-inspect
# HTTPS, so the system CA chain shows up as "self-signed". Aladin pages are
# public, no auth, no PII — safe to skip verify for the scrape.
_CRAWL_SSL = ssl._create_unverified_context()


def _http_get(url: str, timeout: float = 6.0) -> str:
    """Plain GET that pretends to be a browser. Returns decoded HTML."""
    req = Request(
        url,
        headers={
            # NOT a full Chrome UA on purpose: Yes24 sniffs the UA and serves
            # modern Chrome a JS-rendered SPA shell (search results absent
            # from the HTML). The bare WebKit UA gets the server-rendered
            # page with real /product/goods/ links in the markup.
            "User-Agent": (
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                "AppleWebKit/537.36"
            ),
            "Accept-Language": "ko-KR,ko;q=0.9,en-US;q=0.7,en;q=0.5",
        },
    )
    with urlopen(req, timeout=timeout, context=_CRAWL_SSL) as resp:
        body: bytes = resp.read()
        # Aladin / Yes24 / Kyobo all serve UTF-8 these days.
        for enc in ("utf-8", "euc-kr", "cp949"):
            try:
                return body.decode(enc)
            except UnicodeDecodeError:
                continue
        return body.decode("utf-8", errors="replace")


_TAG_RE = re.compile(r"<[^>]+>")
_WS_RE = re.compile(r"[ \t]+")
_BLANK_RE = re.compile(r"\n{2,}")


def _strip_html(s: str) -> str:
    """HTML → plain text. Preserves line breaks from <br>, <li>, <p>, <div>."""
    if not s:
        return ""
    s = re.sub(r"<br\s*/?>", "\n", s, flags=re.I)
    s = re.sub(r"</(li|p|div|tr)>", "\n", s, flags=re.I)
    s = _TAG_RE.sub("", s)
    s = html_lib.unescape(s)
    s = _WS_RE.sub(" ", s)
    s = _BLANK_RE.sub("\n", s)
    return s.strip()


# Patterns used to slice the 목차 block out of a Yes24 product page. The TOC
# is in a hidden <textarea class="txtContentText"> inside <div id="infoset_toc">
# — we MUST scope to that div because Yes24 reuses the same textarea class
# for 책 소개 / 저자 코멘트 / 출판사 리뷰 sections too.
_INFOSET_TOC_RE = re.compile(
    r'<div[^>]+id="infoset_toc[^"]*"[^>]*>(.*?)</div>\s*</div>\s*</div>',
    re.S | re.I,
)
_INFOSET_TOC_TEXTAREA_RE = re.compile(
    r'<textarea[^>]+class="[^"]*txtContentText[^"]*"[^>]*>(.*?)</textarea>',
    re.S | re.I,
)


def _extract_toc_text(html: str) -> str:
    """Pull the 목차 block out of a Yes24 product page. Returns "" if the page
    doesn't have a TOC section (e.g. picture books, 어린왕자 in some editions).

    Strict scope: only the textarea inside `<div id="infoset_toc">`. The same
    `txtContentText` class is reused for 책 소개 / 저자 코멘트 / 출판사 리뷰
    on Yes24, so a loose match returns the wrong content."""
    if not html:
        return ""
    section = _INFOSET_TOC_RE.search(html)
    if not section:
        return ""
    ta = _INFOSET_TOC_TEXTAREA_RE.search(section.group(1))
    if not ta:
        return ""
    text = _strip_html(ta.group(1))
    return text if len(text) > 20 else ""


_YES24_GOODS_RE = re.compile(r"yes24\.com/Product/Goods/(\d+)", re.I)
_OG_TITLE_RE = re.compile(
    r'<meta[^>]+property="og:title"\s+content="([^"]+)"', re.I
)


_YES24_SEARCH_GOODS_RE = re.compile(r"/[Pp]roduct/[Gg]oods/(\d{4,12})")

_ISBN_JUNK_RE = re.compile(r"[^0-9Xx]")


def _clean_isbn(raw: str) -> str:
    """Normalize an ISBN read off a cover/barcode to bare digits.

    Accepts "979-11-6521-899-2", "ISBN 9788934942467", "89344246X"... and
    returns "" unless the cleaned value is a plausible ISBN-10 or ISBN-13
    (13-digit form must start with 978/979 — a stray barcode number like a
    phone number or price code must not be mistaken for an ISBN)."""
    s = _ISBN_JUNK_RE.sub("", str(raw or "")).upper()
    if len(s) == 13 and s.isdigit() and s.startswith(("978", "979")):
        return s
    if len(s) == 10 and s[:9].isdigit() and (s[9].isdigit() or s[9] == "X"):
        return s
    return ""


async def _find_yes24_ids(
    title: str, author: str, publisher: str, edition: str = "", isbn: str = ""
) -> tuple[list[str], set[str]]:
    """Get Yes24 goods IDs for this book — NO LLM involved.

    Returns (ids, isbn_ids): `isbn_ids` marks candidates that came from an
    ISBN query — those identify the exact edition, so the caller can skip
    the fuzzy title gate and boost their score.

    Yes24's desktop search (`/product/search?domain=BOOK&query=...`) is
    server-rendered HTML: the `/product/goods/{id}` links are right in the
    markup, in relevance order. An ISBN query pinpoints the exact edition,
    so it runs FIRST and, when it hits, is used alone — no dilution with
    fuzzier queries. Otherwise: title+publisher+edition, then title+author,
    then bare title. ~1-2s per query, deterministic, free."""
    t = title.strip()
    clean_isbn = _clean_isbn(isbn)
    if not t and not clean_isbn:
        return [], set()
    queries: list[str] = []
    if clean_isbn:
        queries.append(clean_isbn)
    if t:
        if publisher.strip() and edition.strip():
            queries.append(f"{t} {publisher.strip()} {edition.strip()}")
        if publisher.strip():
            queries.append(f"{t} {publisher.strip()}")
        if author.strip():
            queries.append(f"{t} {author.strip()}")
        queries.append(t)

    seen: list[str] = []
    isbn_ids: set[str] = set()
    for q in queries:
        url = (
            "https://www.yes24.com/product/search?domain=BOOK&query="
            + quote_plus(q)
        )
        try:
            html = await asyncio.to_thread(_http_get, url, 6.0)
        except Exception:
            continue
        for m in _YES24_SEARCH_GOODS_RE.finditer(html or ""):
            gid = m.group(1)
            if gid not in seen:
                seen.append(gid)
                if q == clean_isbn:
                    isbn_ids.add(gid)
        # An ISBN hit IS the exact edition — use it alone, don't dilute
        # with fuzzier queries.
        if q == clean_isbn and seen:
            return seen[:4], isbn_ids
        # The publisher-scoped query is the most precise — if it already
        # produced candidates, don't dilute them with broader queries.
        if len(seen) >= 4:
            break
    return seen[:8], isbn_ids


def _title_match(og_title: str, want_title: str) -> bool:
    """Does the Yes24 og:title actually reference the book the user asked for?

    og:title format: "Title | Author | Publisher - 예스24". We slice off the
    Yes24 suffix and require the user's title to appear as a substring of
    the leading title segment. This blocks "약용 식물" being mistaken for
    "데미안" just because the model handed us a stale URL."""
    if not og_title or not want_title:
        return False
    # Drop the " - 예스24" tail.
    head = og_title.split(" - 예스24")[0]
    # Use the leading segment before the first " | " (the actual book title).
    first = head.split(" | ", 1)[0].strip().lower()
    want = want_title.strip().lower()
    # Compare with spacing/punctuation stripped: Yes24 prints "총 균 쇠"
    # (or "총, 균, 쇠") where the user typed "총균쇠" — same book.
    norm = re.compile(r"[\s,.·:\-‘’'\"]+")
    first_n = norm.sub("", first)
    want_n = norm.sub("", want)
    if not first_n or not want_n:
        return False
    return want_n in first_n or first_n in want_n


_EDITION_NORM_RE = re.compile(r"[\s,.·:\-‘’'\"()\[\]]+")


def _edition_match(og_title: str, edition: str) -> bool:
    """Does the Yes24 product title mention this edition? Used as a scoring
    boost (not a gate): '개정증보판'/'뉴에디션'/'10주년' style markers usually
    appear in the product title when an edition matters."""
    if not og_title or not edition:
        return False
    og_n = _EDITION_NORM_RE.sub("", og_title).lower()
    ed_n = _EDITION_NORM_RE.sub("", edition).lower()
    if not ed_n:
        return False
    if ed_n in og_n:
        return True
    # Fall back to token-level: any meaningful chunk of the edition string
    # ("개정증보", "2판", "리커버") found in the product title counts.
    for tok in re.split(r"[\s,./·]+", edition.strip()):
        tok_n = _EDITION_NORM_RE.sub("", tok).lower()
        if len(tok_n) >= 2 and tok_n in og_n:
            return True
    return False


async def crawl_toc_for_book(
    title: str, author: str = "", publisher: str = "", edition: str = "",
    isbn: str = "",
) -> dict:
    """Find a Yes24 product page for this book and pull its 목차 verbatim.

    Strategy:
      1) Scrape Yes24's own search results for candidate goods IDs (no LLM).
         An ISBN (read off the cover barcode) pinpoints the exact edition.
      2) Fetch the top 4 candidates IN PARALLEL (bounded so we don't burn
         50+ seconds chasing dead URLs sequentially).
      3) For each candidate, verify og:title actually names this book and
         score by ISBN provenance + edition + publisher match + TOC length.
      4) Return the printed TOC verbatim from the winning page."""
    ids, isbn_ids = await _find_yes24_ids(title, author, publisher, edition, isbn)
    if not ids:
        return {"toc_text": "", "source_url": ""}

    # Parallel fetch of the top candidates. 6s per fetch × 4 candidates in
    # parallel ≈ 6-8s total, vs ~40s if we walked them sequentially.
    candidates = ids[:4]
    async def fetch(gid: str) -> tuple[str, str, bool]:
        url = f"https://www.yes24.com/Product/Goods/{gid}"
        try:
            html = await asyncio.to_thread(_http_get, url, 6.0)
        except Exception:
            return (url, "", gid in isbn_ids)
        return (url, html or "", gid in isbn_ids)

    results = await asyncio.gather(*(fetch(g) for g in candidates))

    best: dict = {"toc_text": "", "source_url": "", "score": -1}
    for product_url, html, from_isbn in results:
        if not html:
            continue
        og = _OG_TITLE_RE.search(html)
        og_title = og.group(1) if og else ""
        # HARD GATE: title must match. Drops wrong-book hits. ISBN-sourced
        # candidates skip it — the barcode already identified the edition,
        # and Yes24's product title may format the same book differently
        # ("총, 균, 쇠" vs "총 균 쇠") than the cover read gave us.
        if not from_isbn and not _title_match(og_title, title):
            continue
        toc_text = _extract_toc_text(html)
        if not toc_text or len(toc_text) < 20:
            continue
        score = len(toc_text)
        # Barcode-exact edition beats every fuzzy signal below.
        if from_isbn:
            score += 500_000
        if publisher and publisher in og_title:
            score += 100_000
        # Edition marker beats everything else — same title + same publisher
        # can still be 구판 vs 개정판 with subtly different chapter lists,
        # and that's exactly what the reader notices ("목차가 조금 달라").
        if _edition_match(og_title, edition):
            score += 200_000
        if "오디오북" in og_title or "audiobook" in og_title.lower():
            score -= 30_000
        if "중고샵" in og_title:
            score -= 20_000
        if score > best["score"]:
            best = {
                "toc_text": toc_text[:8000],
                "source_url": product_url,
                "score": score,
            }
    if best["toc_text"]:
        return {"toc_text": best["toc_text"], "source_url": best["source_url"]}
    return {"toc_text": "", "source_url": ""}


def _get_client() -> genai.Client:
    global _client
    if _client is None:
        api_key = os.environ.get("GEMINI_API_KEY")
        if not api_key:
            raise RuntimeError("GEMINI_API_KEY is not set")
        _client = genai.Client(api_key=api_key)
    return _client


TOC_PROMPT = """You are a literary expert. The user wants the table of contents
(목차) of this book so they can listen to chapter-by-chapter ambient music.

Book identification:
- Title: "{name}"
- Author: {author}
- Publisher (출판사): {publisher}
- Translator (번역가): {translator}
- Edition / printing: {edition}

YOUR JOB — return the REAL chapter list, in order, with as much detail as you
have. The priority order is:

  (a) IF you know the specific edition above, return that edition's printed
      chapter list (Korean for a Korean publisher, English for a Penguin
      edition, etc.).
  (b) ELSE IF you know this book but not this specific edition, return the
      canonical chapter list of the MOST WIDELY READ edition. Korean reader
      → Korean translation. State which edition you used in `matched_edition`.
  (c) ELSE IF the title alone is ambiguous (e.g. "1984" could be Orwell or
      Murakami), pick the most famous book by that title/author combo and
      return ITS chapters.
  (d) ONLY return `known: false` with empty `chapters` if the title is not a
      real published book that you can identify at all (gibberish, made-up,
      etc.). Real books with thousands of readers — even niche ones — should
      always return chapters.

Output schema (JSON):
{
  "known": bool,
  "matched_edition": str,   // ONE Korean sentence: which edition you used
  "chapters": [
    {"idx": int, "title": str, "summary": str, "music_prompt": str,
     "bpm": int, "mood": str}
  ]
}

For each chapter:
- idx: starts at 0, sequential and gapless.
- title: the chapter's actual printed name in the edition's language.
- summary: ONE short Korean sentence about what happens in this chapter.
- music_prompt: ONE concise English line for a music-generation model —
  name instruments, mood word, genre, and tempo feel. Each chapter MUST
  differ from the previous.
- bpm: integer 40–160 matching the chapter's pace.
- mood: one short Korean word like "고요", "긴장", "슬픔", "환희", "신비".

Skip front-matter (서문, 추천사, 옮긴이의 말) UNLESS the edition treats them
as numbered chapters. Include prologues and epilogues. Up to 50 chapters.
Minimum 3 chapters for any real book — if a book has fewer "chapters" use
the natural sections/parts/acts.

Return ONLY the JSON object. No prose, no markdown."""


# Last-ditch prompt when the strict pass returned known=false. We drop the
# edition-grounding pressure and let the model use whatever it knows.
TOC_PROMPT_FALLBACK = """The user is looking for the table of contents of the
book titled "{name}"{author_hint}. Return the most canonical chapter list
you know for any edition of this book.

If the title alone is ambiguous (e.g. "변신" could be Kafka's
"Die Verwandlung" or another book), use the MOST FAMOUS book by that title.

Output JSON only:
{
  "known": bool,
  "matched_edition": str,
  "chapters": [
    {"idx": int, "title": str, "summary": str, "music_prompt": str,
     "bpm": int, "mood": str}
  ]
}

- idx: 0-indexed sequential.
- title: chapter name in the original or most-common Korean translation.
- summary: one short Korean sentence.
- music_prompt: one English line (instruments + mood + tempo feel).
- bpm: 40–160.
- mood: one Korean word.

Real published book → must return ≥3 chapters. Only return known=false if the
title is genuinely not a real book. No prose."""


async def generate_book_toc(
    book_name: str,
    author: str = "",
    publisher: str = "",
    translator: str = "",
    edition: str = "",
) -> dict:
    """Real TOC of the SPECIFIC edition identified by name + publisher.

    Passing publisher matters: 같은 책이라도 출판사에 따라 챕터 제목과
    구성이 다를 수 있어요 (예: 민음사 1984 vs 문학동네 1984). publisher가
    있으면 그 판본의 실제 차례를 가져옵니다. 책을 진짜로 알지 못하면
    raises — invented filler 대신 정직한 실패."""
    client = _get_client()
    prompt = (
        TOC_PROMPT.replace("{name}", book_name.strip())
        .replace("{author}", author.strip() or "(미상)")
        .replace("{publisher}", publisher.strip() or "(미상)")
        .replace("{translator}", translator.strip() or "(해당 없음)")
        .replace("{edition}", edition.strip() or "(미상)")
    )

    async def _call(model_name: str, prompt_text: str) -> dict:
        resp = await asyncio.to_thread(
            client.models.generate_content,
            model=model_name,
            contents=prompt_text,
            config=types.GenerateContentConfig(
                response_mime_type="application/json",
                temperature=0.2,
                # Big output budget so 30-chapter TOCs aren't truncated.
                max_output_tokens=16384,
            ),
        )
        return json.loads(resp.text or "{}")

    def _parse(raw_in: dict | list) -> tuple[bool, str, list]:
        if isinstance(raw_in, list):
            return True, "", raw_in
        if isinstance(raw_in, dict):
            return (
                bool(raw_in.get("known", True)),
                str(raw_in.get("matched_edition", "")).strip()[:300],
                raw_in.get("chapters") or [],
            )
        return False, "", []

    # Pass 1: edition-grounded, on the stronger model.
    last_exc: Exception | None = None
    known, matched_edition, chapters_raw = False, "", []
    for model in ("gemini-3.5-flash", "gemini-3.1-flash-lite"):
        try:
            raw = await _call(model, prompt)
            known, matched_edition, chapters_raw = _parse(raw)
            if known and chapters_raw:
                last_exc = None
                break
            last_exc = None  # call worked but model said unknown
        except Exception as exc:
            last_exc = exc

    # Pass 2: relaxed fallback prompt — drop edition pressure, ask for the
    # canonical TOC by title alone.
    if not (known and chapters_raw):
        author_hint = f" by {author.strip()}" if author.strip() else ""
        fallback_prompt = TOC_PROMPT_FALLBACK.replace(
            "{name}", book_name.strip()
        ).replace("{author_hint}", author_hint)
        for model in ("gemini-3.5-flash", "gemini-3.1-flash-lite"):
            try:
                raw = await _call(model, fallback_prompt)
                known, matched_edition2, chapters_raw = _parse(raw)
                if known and chapters_raw:
                    if not matched_edition:
                        matched_edition = (
                            matched_edition2 or "표준 판본 (특정 판본 미확인)"
                        )
                    last_exc = None
                    break
            except Exception as exc:
                last_exc = exc

    if last_exc is not None and not chapters_raw:
        raise RuntimeError(f"목차 생성 호출 실패: {last_exc}")

    if not chapters_raw:
        raise RuntimeError(
            "AI가 이 책의 실제 목차를 알지 못해요. 제목과 출판사를 다시 확인해주세요."
        )

    out: list[dict] = []
    for item in chapters_raw:
        if not isinstance(item, dict):
            continue
        title = str(item.get("title", "")).strip()
        if not title:
            continue
        summary = str(item.get("summary", "")).strip()
        music = str(item.get("music_prompt", "")).strip() or "soft ambient pad"
        mood = str(item.get("mood", "")).strip() or "차분"
        try:
            bpm = max(40, min(160, int(item.get("bpm", 80))))
        except (ValueError, TypeError):
            bpm = 80
        out.append(
            {
                "idx": len(out),
                "title": title[:120],
                "summary": summary[:280],
                "music_prompt": music[:200],
                "bpm": bpm,
                "mood": mood[:30],
            }
        )
        if len(out) >= 50:
            break
    if not out:
        raise RuntimeError("AI 응답에서 유효한 챕터를 찾지 못했어요")
    return {"chapters": out, "matched_edition": matched_edition}


COVER_PROMPT = """You are looking at a photo of a book taken with a phone or
laptop camera. Do EVERYTHING in this single call:

1) READ the cover, spine, and back — title, author, publisher, translator,
   edition / printing markers (개정증보판, 1판/2판, n쇄, ISBN if visible).
2) IDENTIFY the book. Use what you read + cover art + your knowledge of
   famous books — even partial reads are enough.
3) WRITE the table of contents for THIS edition. Use your knowledge of
   the publisher above (e.g. 민음사 1984 vs 문학동네 1984 have different
   chapter splits — match the cover).

For each chapter:
- title: actual printed chapter name in the edition's language.
- summary: ONE short Korean sentence about what happens.
- music_prompt: ONE English line for an ambient music model — instruments,
  mood word, genre, tempo feel. Each chapter MUST differ from the previous.
- bpm: integer 40-160 matching the chapter's pace.
- mood: ONE Korean word like "고요", "긴장", "환희", "신비", "슬픔".

Skip front-matter (서문, 추천사, 옮긴이의 말) UNLESS the edition treats them
as numbered chapters. Include prologues and epilogues. 3-50 chapters. For a
real published book, ALWAYS return chapters.

Return JSON only (no markdown, no prose):
{
  "title": str,
  "author": str,
  "publisher": str,
  "translator": str,
  "edition": str,
  "isbn": str,
  "confidence": float,
  "evidence": str,
  "matched_edition": str,
  "chapters": [
    {"idx": int, "title": str, "summary": str, "music_prompt": str,
     "bpm": int, "mood": str}
  ]
}

Rules:
- title: in the edition's language. NEVER empty unless the image is not a book.
- author: original author. Translator goes in `translator`.
- publisher: as printed (민음사 / 문학동네 / 창비 / Penguin). "" if not visible.
- translator: 번역가 if visible, else "".
- edition: any edition marker verbatim, else "".
- isbn: the ISBN if readable — from the back-cover barcode block, the
  copyright page, or an "ISBN 979-11-..." line. DIGITS ONLY (strip hyphens
  and the "ISBN" prefix). "" if not clearly readable — NEVER guess one.
- confidence: 0.0-1.0. 0.4+ recognized, 0.7+ very sure, 0.9+ crystal clear.
- evidence: ONE Korean sentence — what you read and how you identified it.
- matched_edition: ONE Korean sentence stating which edition's TOC you used.
- chapters: idx 0-indexed sequential. Only return [] if the image truly
  shows no book.

No prose, only JSON."""


# Format raw TOC text scraped from a bookstore product page into our
# structured chapter JSON. The crawler gives us reliable chapter strings;
# this prompt's job is only to add mood / bpm / summary / music_prompt.
# The chapter LIST itself (count, order, titles) is fixed by the scraped
# page before this prompt runs — the model cannot add, drop, or rename.
TOC_FORMAT_PROMPT = """Below is the EXACT table of contents of the book
"{title}" by {author} (출판사: {publisher}), one chapter per line, each
prefixed with its index in brackets.

The chapter list is FINAL. Do not add, remove, merge, reorder, or rename
anything. Your only job is to annotate EVERY index with music metadata.

Chapters:
---
{toc_text}
---

For EACH index above (all of them, including 서문/프롤로그/부 표제/에필로그
lines), output:
- idx: the index from the brackets, unchanged.
- summary: ONE short Korean sentence about this part of the book (from your
  knowledge of the book; "" if you don't know it).
- music_prompt: ONE English line for an ambient-music model — instruments,
  mood word, genre, tempo feel. Vary across chapters.
- bpm: integer 40-160 matching the chapter's pace.
- mood: ONE Korean word like "고요", "긴장", "슬픔", "환희", "신비".

Return JSON only:
{
  "chapters": [
    {"idx": int, "summary": str, "music_prompt": str, "bpm": int, "mood": str}
  ]
}"""


def _normalize_chapter(item: dict, idx: int) -> dict | None:
    """Clamp/clean one chapter object coming back from the model."""
    title = str(item.get("title", "")).strip()
    if not title:
        return None
    summary = str(item.get("summary", "")).strip()
    music = str(item.get("music_prompt", "")).strip() or "soft ambient pad"
    mood = str(item.get("mood", "")).strip() or "차분"
    try:
        bpm = max(40, min(160, int(item.get("bpm", 80))))
    except (ValueError, TypeError):
        bpm = 80
    return {
        "idx": idx,
        "title": title[:120],
        "summary": summary[:280],
        "music_prompt": music[:200],
        "bpm": bpm,
        "mood": mood[:30],
    }


async def _format_scraped_toc(
    toc_text: str, title: str, author: str, publisher: str
) -> list[dict]:
    """Take raw text scraped off a bookstore page and turn it into our
    structured chapter list. The Gemini call here is text-only and cheap —
    its job is only mood/bpm/summary, the chapter strings come straight from
    the page so they're already correct."""
    # The scraped page IS the TOC: every line, verbatim, in order. Gemini
    # never gets to choose the chapter list — earlier versions let it
    # "structure" the raw text and it silently dropped 서문/부 표제/연대표
    # lines, so the app's TOC stopped matching the printed book.
    base = _parse_scraped_toc_locally(toc_text)
    if not base:
        return []
    try:
        client = _get_client()
    except Exception:
        # No API key at all — verbatim titles with generic music metadata.
        return base
    numbered = "\n".join(f"[{c['idx']}] {c['title']}" for c in base)
    prompt = (
        TOC_FORMAT_PROMPT
        .replace("{title}", title or "(미상)")
        .replace("{author}", author or "(미상)")
        .replace("{publisher}", publisher or "(미상)")
        .replace("{toc_text}", numbered[:8000])
    )
    for model in ("gemini-3.1-flash-lite", "gemini-3.5-flash"):
        try:
            resp = await asyncio.to_thread(
                client.models.generate_content,
                model=model,
                contents=prompt,
                config=types.GenerateContentConfig(
                    response_mime_type="application/json",
                    temperature=0.2,
                ),
            )
            raw = json.loads(resp.text or "{}")
            items = raw.get("chapters") if isinstance(raw, dict) else raw
            if not isinstance(items, list):
                continue
            by_idx: dict[int, dict] = {}
            for it in items:
                if not isinstance(it, dict):
                    continue
                try:
                    by_idx[int(it.get("idx", -1))] = it
                except (ValueError, TypeError):
                    continue
            if not by_idx:
                continue
            for c in base:
                it = by_idx.get(c["idx"])
                if not it:
                    continue
                summary = str(it.get("summary", "")).strip()
                music = str(it.get("music_prompt", "")).strip()
                mood = str(it.get("mood", "")).strip()
                # Keep page-derived summaries (folded sub-topic lists) — the
                # printed sub-topics beat a generated one for detection.
                if summary and not c["summary"]:
                    c["summary"] = summary[:280]
                if music:
                    c["music_prompt"] = music[:200]
                if mood:
                    c["mood"] = mood[:30]
                try:
                    c["bpm"] = max(40, min(160, int(it.get("bpm", c["bpm"]))))
                except (ValueError, TypeError):
                    pass
            return base
        except Exception:
            continue
    # Gemini unavailable (expired key, quota, outage) — ship the verbatim
    # list with generic music metadata rather than returning nothing.
    return base


_PAGE_NO_TAIL_RE = re.compile(r"[\s.·…]*\d{1,4}\s*$")
_TOC_NOISE_RE = re.compile(
    r"^(판권|저자\s*소개|작가\s*소개|책\s*속으로|출판사\s*리뷰|회원\s*리뷰)",
)
# Some pages cram several chapters into one line without a separator
# ("…천국과 지옥Chapter 5 붉은 행성을…") — split before each heading token.
_INLINE_CH_SPLIT_RE = re.compile(
    r"(?<=.)(?=Chapter\s*\d+|제\s*\d+\s*[장부편권화])"
)


def _parse_scraped_toc_locally(toc_text: str) -> list[dict]:
    """One TOC line → one chapter, title VERBATIM from the bookstore page
    (only trailing page numbers stripped). Music metadata is generic — Lyria
    still gets a usable prompt, and chapter DETECTION (the part that needs
    exact titles) stays perfect. Gemini may overwrite the metadata later but
    never the titles."""
    out: list[dict] = []
    lines: list[str] = []
    for raw_line in toc_text.splitlines():
        lines.extend(_INLINE_CH_SPLIT_RE.split(raw_line))
    for line in lines:
        t = _PAGE_NO_TAIL_RE.sub("", line.strip()).strip()
        if len(t) < 2 or _TOC_NOISE_RE.match(t):
            continue
        # "소주제A / 소주제B / 소주제C" lines are the sub-topics UNDER the
        # previous heading, not chapters of their own (kids'/econ books print
        # their TOC this way). Fold them into the heading's summary — they're
        # exactly what the camera will see in body pages, so they make great
        # detection evidence.
        if t.count(" / ") >= 2 and out:
            if not out[-1]["summary"]:
                out[-1]["summary"] = t[:280]
            continue
        out.append(
            {
                "idx": len(out),
                "title": t[:120],
                "summary": "",
                "music_prompt": "calm ambient reading music, soft piano and warm pads",
                "bpm": 80,
                "mood": "차분",
            }
        )
        if len(out) >= 50:
            break
    return out


async def identify_book_from_cover(image_bytes: bytes) -> dict:
    """Single vision call returns cover identification + chapters together.

    The previous design chained 3 Gemini calls (vision → crawler → format)
    which took 20-40s end-to-end. Now everything fits in one vision call so
    the user sees results in ~5-10s. Crawler stays available for the
    /camera/toc-lookup refetch path if the user wants edition-grounded data
    after correcting fields by hand."""
    client = _get_client()

    async def _vision_call(model_name: str) -> dict:
        resp = await asyncio.to_thread(
            client.models.generate_content,
            model=model_name,
            contents=[
                COVER_PROMPT,
                types.Part.from_bytes(data=image_bytes, mime_type="image/jpeg"),
            ],
            config=types.GenerateContentConfig(
                response_mime_type="application/json",
                temperature=0.1,
                # Give the model plenty of room — TOC for a 25-chapter book
                # with all per-chapter fields is ~2-3K tokens. Default cap
                # truncated longer books mid-array.
                max_output_tokens=16384,
            ),
        )
        return json.loads(resp.text or "{}")

    # Use flash directly. Lite-first cascade truncated chapter arrays for
    # longer books — accuracy matters more than 2-3s of latency here.
    last_exc: Exception | None = None
    raw: dict = {}
    for model in ("gemini-3.5-flash", "gemini-3.1-flash-lite"):
        try:
            raw = await _vision_call(model)
            last_exc = None
            break
        except Exception as exc:
            last_exc = exc
    if last_exc is not None:
        raise RuntimeError(f"표지 인식 호출 실패: {last_exc}")

    def _clean(field: str, limit: int) -> str:
        return str(raw.get(field, "")).strip()[:limit]

    try:
        conf = float(raw.get("confidence", 0.0))
    except (ValueError, TypeError):
        conf = 0.0

    title = _clean("title", 200)
    author = _clean("author", 120)
    publisher = _clean("publisher", 120)
    translator = _clean("translator", 120)
    edition = _clean("edition", 80)
    isbn = _clean_isbn(raw.get("isbn", ""))
    matched_edition = _clean("matched_edition", 300)
    source_url = ""

    # Parse the inline TOC the single vision call returns.
    chapters: list[dict] = []
    for item in raw.get("chapters") or []:
        if not isinstance(item, dict):
            continue
        ch = _normalize_chapter(item, len(chapters))
        if ch:
            chapters.append(ch)
        if len(chapters) >= 50:
            break

    return {
        "title": title,
        "author": author,
        "publisher": publisher,
        "translator": translator,
        "edition": edition,
        "isbn": isbn,
        "confidence": max(0.0, min(1.0, conf)),
        "evidence": _clean("evidence", 300),
        "matched_edition": matched_edition,
        "source_url": source_url,
        "chapters": chapters,
    }


TOC_PAGE_PROMPT = """You are looking at a photo of the printed TABLE OF
CONTENTS (목차 / 차례 / Contents) page of a physical book, taken with a phone
or laptop camera.{book_hint}

TRANSCRIBE the printed table of contents EXACTLY:
- Extract EVERY visible entry, top to bottom, in printed order.
- Copy each title VERBATIM in the printed language — do NOT translate,
  shorten, "fix", reorder, merge, or invent entries.
- Strip trailing dot leaders and page numbers
  ("3장 도시의 밤 ……… 87" → "3장 도시의 밤") but keep chapter numbers
  that are part of the title ("제3장", "Chapter 5").
- Part/section headings (제1부, Part II, 1부 …) are entries of their own.
- Small indented sub-topic lines printed UNDER a chapter belong in that
  chapter's `summary`, joined with " / " — NOT as separate entries.
- Skip page furniture: the "목차/차례/Contents" heading itself, page
  numbers of the TOC page, decorative rules.
- If the photo shows two facing TOC pages, read left page first, then right.

Also annotate every entry for an ambient-music generator:
- summary: printed sub-topics if present; else ONE short Korean sentence
  about this part of the book if you know it; else "".
- music_prompt: ONE concise English line — instruments, mood word, genre,
  tempo feel. Vary across entries.
- bpm: integer 40-160 matching the part's pace.
- mood: ONE short Korean word like "고요", "긴장", "슬픔", "환희", "신비".

If the photo is NOT a table-of-contents page (body text, cover, a hand,
too blurry to read), return is_toc_page=false and say why in ONE short
Korean sentence in `reason`.

Return JSON only:
{
  "is_toc_page": bool,
  "reason": str,
  "entries": [
    {"title": str, "summary": str, "music_prompt": str, "bpm": int,
     "mood": str}
  ]
}

No prose, only the JSON."""


async def extract_toc_from_photo(image_bytes: bytes, book_name: str = "") -> dict:
    """Read the printed TOC verbatim from a photo of the book's 목차 page.

    ONE vision call per photo — transcription and music annotation together
    (same single-call principle as identify_book_from_cover). This is the
    accuracy ceiling for TOC discovery: the page in the reader's hands IS
    the ground truth, so it works even for books no bookstore indexes.
    Multi-page TOCs are handled by the client photographing page by page
    and merging — the server stays stateless."""
    client = _get_client()
    clean_bytes, _sharpness, _ok = _enhance_for_vision(image_bytes)
    hint = (
        f' The book is "{book_name.strip()}" — use that only to disambiguate'
        " hard-to-read characters, never to substitute chapters you expect."
        if book_name.strip()
        else ""
    )
    prompt = TOC_PAGE_PROMPT.replace("{book_hint}", hint)

    async def _call(model_name: str) -> dict:
        resp = await asyncio.to_thread(
            client.models.generate_content,
            model=model_name,
            contents=[
                prompt,
                types.Part.from_bytes(data=clean_bytes, mime_type="image/jpeg"),
            ],
            config=types.GenerateContentConfig(
                response_mime_type="application/json",
                temperature=0.1,
                # Dense TOCs (40+ entries with annotations) need headroom.
                max_output_tokens=16384,
            ),
        )
        return json.loads(resp.text or "{}")

    # OCR fidelity matters most here — flash first, lite as fallback.
    last_exc: Exception | None = None
    raw: dict = {}
    for model in ("gemini-3.5-flash", "gemini-3.1-flash-lite"):
        try:
            raw = await _call(model)
            last_exc = None
            break
        except Exception as exc:
            last_exc = exc
    if last_exc is not None:
        raise RuntimeError(f"목차 페이지 판독 호출 실패: {last_exc}")

    is_toc_page = bool(raw.get("is_toc_page", False))
    reason = str(raw.get("reason", "")).strip()[:300]
    chapters: list[dict] = []
    for item in raw.get("entries") or []:
        if not isinstance(item, dict):
            continue
        ch = _normalize_chapter(item, len(chapters))
        if ch:
            chapters.append(ch)
        if len(chapters) >= 50:
            break
    return {"is_toc_page": is_toc_page, "reason": reason, "chapters": chapters}


DETECT_PROMPT = """A reader is reading "{book_name}". The camera just snapped
the page they are on. Your job is OCR-FIRST chapter detection:

PRIMARY SIGNAL — look for a CHAPTER HEADING. Book pages typically print the
chapter title as the LARGEST text on the page, often centered, often at the
top of a fresh page (with a chapter number like "제3장", "Chapter 5",
"3", or just a standalone title line above a block of body text). If you see
such a heading, that is the chapter — match it against the TOC below by
its printed title or chapter number.

SECONDARY SIGNAL — if no heading is visible (mid-chapter body text only),
read a sentence or two of body text and pick the chapter whose summary best
matches what's happening in those lines. Also use any RUNNING HEADER (the
small chapter/section title often printed at the very top of each page) and
the page number if visible.

CONTINUITY — {current_hint} People read forward, one page at a time, so the
reader is almost always on that same chapter or the next one. Do NOT jump to a
distant chapter unless a heading or running header clearly proves it. If the
body text could fit several chapters, prefer the one the reader is already on
and LOWER your confidence accordingly.

If the image is blank, too blurred to read, or NOT a book page (a hand, a
wall, a phone screen), return chapter_idx = -1 with low confidence.

Chapters in this edition:
{toc_text}

Return JSON only:
{"chapter_idx": int, "confidence": float, "evidence": str}

- chapter_idx: 0-based TOC index of the matched chapter, or -1 if unsure.
- confidence: 0.0–1.0. Clear heading/running-header → 0.85+. Body-text
  inference that fits one chapter well → 0.5–0.7. Ambiguous body text → <0.4.
- evidence: ONE short Korean sentence — quote the heading or fragment you
  read, e.g. "표지 가운데에 '제3장 사임의 신어 사전'을 읽음".

No prose, only the JSON."""


# Vision preprocessing knobs. Phone photos arrive rotated, dim, or slightly
# soft; a cheap clean-up before OCR markedly improves heading legibility.
_VISION_MIN_DIM = 640    # upscale shots smaller than this so small text is readable
_VISION_MAX_DIM = 1280   # cap the long edge so we don't ship huge payloads
# Edge-energy stddev below this means "probably too blurry to trust a switch".
# Calibrated for the FIND_EDGES metric below; sharp text pages score well above.
_BLUR_THRESHOLD = 9.0


def _sharpness_score(img: "Image.Image") -> float:
    """Cheap blur metric: stddev of an edge-filtered grayscale image. Higher is
    sharper. Blurry / low-detail photos score low. No numpy needed."""
    edges = img.convert("L").filter(ImageFilter.FIND_EDGES)
    return float(ImageStat.Stat(edges).stddev[0])


def _enhance_for_vision(image_bytes: bytes) -> tuple[bytes, float, bool]:
    """Best-effort clean-up of a camera photo before sending it to vision.

    Returns (jpeg_bytes, sharpness_score, ok). On any failure returns the
    original bytes with ok=False so callers don't penalize an unmeasured image.
    """
    try:
        img = Image.open(io.BytesIO(image_bytes))
        img = ImageOps.exif_transpose(img)  # honor phone rotation
        img = img.convert("RGB")
        w, h = img.size
        short = min(w, h)
        if 0 < short < _VISION_MIN_DIM:
            scale = _VISION_MIN_DIM / short
            img = img.resize((round(w * scale), round(h * scale)), Image.LANCZOS)
        longest = max(img.size)
        if longest > _VISION_MAX_DIM:
            scale = _VISION_MAX_DIM / longest
            img = img.resize(
                (round(img.size[0] * scale), round(img.size[1] * scale)), Image.LANCZOS
            )
        score = _sharpness_score(img)  # measure BEFORE sharpening
        img = ImageOps.autocontrast(img, cutoff=1)  # fix dark / uneven lighting
        img = img.filter(ImageFilter.UnsharpMask(radius=2, percent=120, threshold=2))
        out = io.BytesIO()
        img.save(out, format="JPEG", quality=88)
        return out.getvalue(), score, True
    except Exception as exc:  # noqa: BLE001 - best-effort, never block detection
        print(f"[camera] image enhance failed: {exc!r}")
        return image_bytes, 0.0, False


# Confidence gates for accepting a chapter switch. Hysteresis: staying put is
# free, switching costs confidence — and switching far costs more.
_CONF_ACCEPT = 0.6        # high enough to jump to ANY chapter
_CONF_ADJACENT = 0.4      # only enough for a neighbouring chapter (a page turn)
_CONF_ACCEPT_LOWQ = 0.78  # blurry shot → demand much more before switching
_CONF_ADJACENT_LOWQ = 0.55


def decide_chapter(
    current_idx: int,
    detected_idx: int,
    confidence: float,
    num_chapters: int,
    low_quality: bool = False,
    locked: bool = True,
) -> int:
    """Decide the active chapter from a fresh detection + the current state.

    Principles:
      - A bad read (-1 / out of range) NEVER drops us to silence — we hold the
        current chapter.
      - High confidence may switch to any chapter.
      - Medium confidence may only switch to an ADJACENT chapter (a plausible
        page turn) — this kills jitter where ambiguous body text flings the
        music to an unrelated chapter.
      - Before the first confirmed lock-on, allow medium-confidence positioning
        to anywhere, since the reader may start in the middle of the book.
      - A blurry image raises every bar.
    """
    if num_chapters <= 0:
        return current_idx
    if detected_idx is None or detected_idx < 0 or detected_idx >= num_chapters:
        return current_idx  # unreadable / unsure → hold
    if detected_idx == current_idx:
        return current_idx
    accept = _CONF_ACCEPT_LOWQ if low_quality else _CONF_ACCEPT
    adjacent = _CONF_ADJACENT_LOWQ if low_quality else _CONF_ADJACENT
    if not locked:
        # Initial positioning: the reader could be anywhere in the book.
        return detected_idx if confidence >= adjacent else current_idx
    if confidence >= accept:
        return detected_idx
    if confidence >= adjacent and abs(detected_idx - current_idx) <= 1:
        return detected_idx
    return current_idx


async def detect_chapter_from_image(
    book_name: str,
    toc: list[dict],
    image_bytes: bytes,
    current_idx: int = -1,
) -> dict:
    if not toc:
        return {
            "chapter_idx": -1,
            "confidence": 0.0,
            "evidence": "목차가 비어있어요",
            "low_quality": False,
        }

    toc_text = "\n".join(
        f"  [{c['idx']}] {c['title']} — {c.get('summary', '')}" for c in toc
    )
    if 0 <= current_idx < len(toc):
        cur = toc[current_idx]
        current_hint = f'last on chapter [{current_idx}] "{cur["title"]}".'
    else:
        current_hint = "not on any chapter yet (just starting)."
    prompt = (
        DETECT_PROMPT.replace("{book_name}", book_name)
        .replace("{toc_text}", toc_text)
        .replace("{current_hint}", current_hint)
    )

    clean_bytes, sharpness, enhanced = _enhance_for_vision(image_bytes)
    low_quality = enhanced and sharpness < _BLUR_THRESHOLD

    client = _get_client()
    try:
        resp = await asyncio.to_thread(
            client.models.generate_content,
            model="gemini-3.1-flash-lite",
            contents=[
                prompt,
                types.Part.from_bytes(data=clean_bytes, mime_type="image/jpeg"),
            ],
            config=types.GenerateContentConfig(
                response_mime_type="application/json",
                temperature=0.1,
            ),
        )
        raw = json.loads(resp.text or "{}")
    except Exception as exc:
        raise RuntimeError(f"Vision 호출 실패: {exc}")

    try:
        idx = int(raw.get("chapter_idx", -1))
    except (ValueError, TypeError):
        idx = -1
    try:
        conf = float(raw.get("confidence", 0.0))
    except (ValueError, TypeError):
        conf = 0.0
    evidence = str(raw.get("evidence", "")).strip()[:300]
    if idx < -1 or idx >= len(toc):
        idx = -1
    return {
        "chapter_idx": idx,
        "confidence": max(0.0, min(1.0, conf)),
        "evidence": evidence,
        "low_quality": bool(low_quality),
    }


CHARACTERS_PROMPT = """You know the book "{book_name}"{bib}. From your
knowledge of this book, list its main characters and their key
relationships. The reader has the PHYSICAL book — there is no text excerpt;
rely on what you know about this specific work.

Table of contents of the reader's edition (for grounding):
{toc_text}

Return JSON with this exact shape:
{
  "characters": [
    {
      "name": "character name in Korean as commonly translated",
      "description": "1-2 Korean sentences — who they are and what they want",
      "first_page": 0,
      "importance": int 1-5 (5 = protagonist, 1 = minor)
    }
  ],
  "relationships": [
    {
      "from": "name1",
      "to": "name2",
      "label": "one short Korean word/phrase: 친구/적/연인/가족/동료/스승/제자/라이벌/...",
      "kind": "friend|enemy|romance|family|colleague|mentor|rival|other"
    }
  ]
}

Strict rules:
- Maximum 12 characters, only the meaningful cast.
- All names and descriptions in Korean.
- first_page is always 0 (the reader has a physical book, not a scanned one).
- Only relationships clearly established in the book.
- Non-fiction: key real figures count as characters (저자 본인 제외); if the
  book genuinely has no people in it, return both arrays empty.
- If you don't actually know this book, return both arrays empty — DO NOT
  invent characters.

Return only the JSON object — no prose, no markdown fences."""


async def generate_book_characters(
    book_name: str,
    author: str = "",
    publisher: str = "",
    toc: list[dict] | None = None,
) -> dict:
    """Single cheap knowledge call: cast + relationships for a named book.
    Same response shape as the PDF reader's characters_extractor so the
    frontend panel is shared."""
    bib_parts = [p for p in (author, publisher) if p]
    bib = f" ({', '.join(bib_parts)})" if bib_parts else ""
    toc_text = "\n".join(f"- {c['title']}" for c in (toc or [])[:50]) or "(없음)"
    prompt = (
        CHARACTERS_PROMPT
        .replace("{book_name}", book_name)
        .replace("{bib}", bib)
        .replace("{toc_text}", toc_text)
    )
    client = _get_client()
    last_exc: Exception | None = None
    for model in ("gemini-3.5-flash", "gemini-3.1-flash-lite"):
        try:
            resp = await asyncio.to_thread(
                client.models.generate_content,
                model=model,
                contents=prompt,
                config=types.GenerateContentConfig(
                    response_mime_type="application/json",
                    temperature=0.2,
                    # Recall task, not reasoning — skipping thinking cuts
                    # ~18s down to a few seconds.
                    thinking_config=types.ThinkingConfig(thinking_budget=0),
                ),
            )
            raw = json.loads(resp.text or "{}")
            break
        except Exception as exc:
            last_exc = exc
            raw = {}
    else:
        raise RuntimeError(f"인물 생성 호출 실패: {last_exc}")

    chars: list[dict] = []
    for it in raw.get("characters", []) if isinstance(raw, dict) else []:
        if not isinstance(it, dict):
            continue
        name = str(it.get("name", "")).strip()
        if not name:
            continue
        try:
            imp = max(1, min(5, int(it.get("importance", 2))))
        except (ValueError, TypeError):
            imp = 2
        chars.append(
            {
                "name": name[:60],
                "description": str(it.get("description", "")).strip()[:280],
                "first_page": 0,
                "importance": imp,
            }
        )
        if len(chars) >= 12:
            break
    names = {c["name"] for c in chars}
    rels: list[dict] = []
    for it in raw.get("relationships", []) if isinstance(raw, dict) else []:
        if not isinstance(it, dict):
            continue
        f, t = str(it.get("from", "")).strip(), str(it.get("to", "")).strip()
        if f not in names or t not in names or f == t:
            continue
        kind = str(it.get("kind", "other")).strip() or "other"
        rels.append(
            {
                "from": f,
                "to": t,
                "label": str(it.get("label", "")).strip()[:30],
                "kind": kind[:20],
            }
        )
        if len(rels) >= 24:
            break
    return {"characters": chars, "relationships": rels}


def camera_session_dir(session_id: str) -> Path:
    d = CAMERA_AUDIO_ROOT / session_id
    d.mkdir(parents=True, exist_ok=True)
    return d


def camera_segment_path(session_id: str, chapter_idx: int) -> Path:
    return camera_session_dir(session_id) / f"chapter_{chapter_idx}.pcm"


async def _capture_chapter_segment(
    session_id: str,
    chapter: dict,
    duration_s: int = SEGMENT_DURATION_S,
    context_prompt: str | None = None,
) -> None:
    path = camera_segment_path(session_id, chapter["idx"])
    if path.exists() and path.stat().st_size >= BYTES_PER_SECOND * duration_s:
        return
    data = await capture_lyria_pcm(
        chapter["music_prompt"], chapter.get("bpm", 80), duration_s,
        context_prompt=context_prompt,
    )
    path.write_bytes(data)


async def generate_all_camera_segments(
    session_id: str, toc: list[dict], context_prompt: str | None = None
) -> None:
    """Generate one PCM segment per chapter. Concurrency-capped to keep
    Lyria happy — same pattern as audio_cache.generate_all_segments."""
    sem = asyncio.Semaphore(3)

    async def _bounded(ch: dict) -> None:
        async with sem:
            await _capture_chapter_segment(session_id, ch, context_prompt=context_prompt)

    await asyncio.gather(*(_bounded(c) for c in toc))


def _trigger_camera_sync(url: str) -> None:
    req = Request(f"{url}/capture?cmd=1", method="POST", data=b"")
    with urlopen(req, timeout=4.0) as resp:
        resp.read()


def _fetch_latest_photo_sync(url: str) -> bytes:
    """Try common upload paths the voicegared server might be using.
    `/photo/image` is the voicegared server's actual binary endpoint;
    the others are fallbacks for variant server forks."""
    paths = (
        "/photo/image",
        "/uploads/photo.jpg",
        "/photo.jpg",
        "/uploads/latest.jpg",
        "/latest.jpg",
    )
    last_err: Exception | None = None
    for path in paths:
        try:
            with urlopen(f"{url}{path}", timeout=4.0) as resp:
                if resp.status == 200:
                    data = resp.read()
                    if len(data) > 800:
                        return data
        except Exception as exc:
            last_err = exc
            continue
    raise RuntimeError(
        f"카메라 서버({url})에서 사진을 가져올 수 없어요. "
        f"마지막 오류: {last_err!r}"
    )


async def trigger_and_fetch_photo(camera_url: str | None = None) -> bytes:
    """Tell the camera server '찍어', wait long enough for the board's
    next poll-and-upload cycle, then grab the freshest photo."""
    url = (camera_url or CAMERA_SERVER_URL).rstrip("/")
    try:
        await asyncio.to_thread(_trigger_camera_sync, url)
    except Exception as exc:
        raise RuntimeError(
            f"카메라 서버({url})에 촬영 신호를 보내지 못했어요: {exc}"
        )
    # Board polls /trigger every ~0.7s and then uploads (~1-2s for JPEG).
    # 3.5s gives both steps room without making the user wait forever.
    await asyncio.sleep(3.5)
    return await asyncio.to_thread(_fetch_latest_photo_sync, url)
