"""Gemini-based TOC extractor.

Used as a fallback when a PDF has no built-in outline. We hand the model a
compact "first few characters of each page" listing and ask it to identify
chapter starts. Cheap on tokens since per-page snippets stay short.
"""

import asyncio
import json
import os

from google import genai
from google.genai import types

_client: genai.Client | None = None


def _get_client() -> genai.Client:
    global _client
    if _client is None:
        api_key = os.environ.get("GEMINI_API_KEY")
        if not api_key:
            raise RuntimeError("GEMINI_API_KEY environment variable is not set")
        _client = genai.Client(api_key=api_key)
    return _client


PROMPT = """You are extracting the table of contents from a book.
Below is the first ~160 characters of each page, prefixed by [p.N].

Return a JSON array of {"title": str, "page": int} ONLY for genuine chapter
starts. Sort ascending by page.

A genuine chapter start has ALL of these:
  - The page begins with a short, complete title — typically a phrase like
    "Chapter 1", "1장.", "Part I", "Prologue", "에필로그", "서문",
    or a numbered heading. Not a sentence fragment.
  - The title is at most ~30 characters. Long phrases that look mid-sentence
    are NOT chapter titles.
  - It is followed by body text, not just another heading.

Strictly avoid:
  - Sentence fragments (anything that reads like the middle of a paragraph).
  - Running headers / repeating boilerplate.
  - Copyright pages, table of contents pages, bibliographies.
  - Marking every page — most books have between 3 and 30 chapters total.

If you cannot identify clear chapter starts, return [].
Cap output at 40 entries.

Pages:
{pages}

Return only the JSON array, no prose."""


async def analyze_toc(pages: list[str]) -> list[dict]:
    if not pages:
        return []
    lines = []
    for i, text in enumerate(pages, start=1):
        snippet = (text or "").replace("\n", " ").strip()[:160]
        if not snippet:
            continue
        lines.append(f"[p.{i}] {snippet}")
    if not lines:
        return []
    pages_block = "\n".join(lines)
    client = _get_client()
    try:
        resp = await asyncio.to_thread(
            client.models.generate_content,
            model="gemini-3.1-flash-lite",
            contents=PROMPT.replace("{pages}", pages_block),
            config=types.GenerateContentConfig(response_mime_type="application/json"),
        )
        raw = json.loads(resp.text or "[]")
    except Exception as exc:
        print(f"[toc_analyzer] failed: {exc!r}")
        return []
    if not isinstance(raw, list):
        return []

    out: list[dict] = []
    seen_pages: set[int] = set()
    for item in raw:
        if not isinstance(item, dict):
            continue
        try:
            title = str(item.get("title", "")).strip()
            page = int(item.get("page", 0))
        except (ValueError, TypeError):
            continue
        if not title or page < 1 or page > len(pages):
            continue
        if page in seen_pages:
            continue
        if len(title) > 60:
            continue  # likely a sentence, not a title
        seen_pages.add(page)
        out.append({"level": 1, "title": title, "page": page})
    out.sort(key=lambda x: x["page"])
    out = out[:40]

    # Density check: if Gemini flagged more than ~half of all pages, it's
    # almost certainly mistaking paragraph headings for chapters. Drop the
    # whole list so the UI shows "no clear TOC" instead of noise.
    if len(out) > 0 and len(out) > max(3, len(pages) // 2):
        print(
            f"[toc_analyzer] dropping {len(out)} entries — too dense for "
            f"{len(pages)} pages (likely noise)"
        )
        return []

    return out
