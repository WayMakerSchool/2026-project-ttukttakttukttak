"""Dictionary lookup — Gemini-powered word/phrase definition with cache.

Given a selected word (and optional surrounding sentence for disambiguation),
returns a compact dictionary entry: Korean definition, hanja (when applicable),
English translation, pronunciation hint, and a short example.

Caches by (word, language_hint) to keep repeated lookups instant and free.
"""

import asyncio
import json
import os
import re
from typing import Any

from google import genai
from google.genai import types

_client: genai.Client | None = None
_cache: dict[str, dict[str, Any]] = {}

MODEL = "gemini-3.1-flash-lite"
MAX_WORD_LEN = 200


def _get_client() -> genai.Client:
    global _client
    if _client is None:
        api_key = os.environ.get("GEMINI_API_KEY")
        if not api_key:
            raise RuntimeError("GEMINI_API_KEY is not set")
        _client = genai.Client(api_key=api_key)
    return _client


SYSTEM = """You are a multilingual dictionary that helps a Korean reader
understand a word or short phrase they selected while reading a book.

Return a STRICT JSON object with these keys (all strings except "translations"):
  word            : the headword, normalized (trim punctuation/spacing)
  pos             : part of speech in Korean (명사/동사/형용사/부사/감탄사/관용구/고유명사…)
  definition      : a clear Korean definition (1~3 short sentences)
  hanja           : the original 한자 if the word is a Sino-Korean word; otherwise ""
  pronunciation   : Korean phonetic hint or IPA for foreign words; "" if obvious
  translations    : { "en": "...", "ja": "..." } — use "" if unknown
  example         : ONE short example sentence in the same language as the word
  note            : optional extra (어원/뉘앙스/유의어), 50자 이내. "" if nothing useful.

Rules:
- Output ONLY the JSON. No markdown fences. No prose before/after.
- Be concise; readers want a quick glance.
- If the input is a phrase or idiom, treat it as a single entry.
- If you genuinely cannot identify the word, return a JSON with
  definition: "이 단어의 뜻을 찾지 못했어요." and empty other fields."""


def _strip_json(raw: str) -> str:
    s = raw.strip()
    if s.startswith("```"):
        s = re.sub(r"^```[a-zA-Z]*\n?", "", s)
        s = re.sub(r"\n?```$", "", s)
    return s.strip()


async def lookup_word(*, word: str, context: str | None = None) -> dict[str, Any]:
    w = (word or "").strip()
    if not w:
        return {"word": "", "definition": "단어를 선택해 주세요."}
    if len(w) > MAX_WORD_LEN:
        w = w[:MAX_WORD_LEN]

    cache_key = f"{w}|{(context or '')[:80]}"
    cached = _cache.get(cache_key)
    if cached is not None:
        return cached

    ctx_line = f"\nCONTEXT SENTENCE: {context.strip()[:240]}" if context else ""
    prompt = f"WORD: {w}{ctx_line}"

    client = _get_client()
    resp = await asyncio.to_thread(
        client.models.generate_content,
        model=MODEL,
        contents=prompt,
        config=types.GenerateContentConfig(
            system_instruction=SYSTEM,
            temperature=0.2,
            max_output_tokens=400,
            response_mime_type="application/json",
        ),
    )

    raw = (resp.text or "").strip()
    try:
        data = json.loads(_strip_json(raw))
    except json.JSONDecodeError:
        data = {"word": w, "definition": raw[:400] or "사전 응답을 해석하지 못했어요."}

    data.setdefault("word", w)
    data.setdefault("definition", "")
    data.setdefault("pos", "")
    data.setdefault("hanja", "")
    data.setdefault("pronunciation", "")
    data.setdefault("translations", {})
    data.setdefault("example", "")
    data.setdefault("note", "")

    _cache[cache_key] = data
    return data
