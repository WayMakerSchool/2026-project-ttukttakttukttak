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


PROMPT = """Below is the first ~140 characters of each page of a book, numbered.
Identify the book's chapter or section starts and return a JSON array of
objects: [{"title": "...", "page": 1-indexed-int}, ...]

Strict rules:
- Only include genuine chapter/section starts (skip running headers, page
  numbers alone, copyright pages, repeating boilerplate).
- `title` should be the chapter title as it appears, trimmed.
- Sort by page ascending. Each page may appear at most once.
- If you cannot find a clear structure, return an empty array [].
- Maximum 40 entries.
- Pages with little text are usually not chapter starts.

Page snippets:
{pages}

Return only the JSON array, no prose."""


async def analyze_toc(pages: list[str]) -> list[dict]:
    if not pages:
        return []
    lines = []
    for i, text in enumerate(pages, start=1):
        snippet = (text or "").replace("\n", " ").strip()[:140]
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
        seen_pages.add(page)
        out.append({"level": 1, "title": title, "page": page})
    out.sort(key=lambda x: x["page"])
    return out[:40]
