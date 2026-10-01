"""Gemini-based chapter summarizer.

When a book has a TOC, we ask Gemini to write a short summary of each chapter
so users can recall "what happened so far" when they re-open the book.
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
            raise RuntimeError("GEMINI_API_KEY is not set")
        _client = genai.Client(api_key=api_key)
    return _client


PROMPT = """Summarize this chapter of a book in 2-3 short sentences in the
SAME LANGUAGE as the chapter text (Korean stays Korean, English stays English).
Focus on what happens or what the chapter is about, not stylistic praise.
Maximum 200 characters.

Chapter title: {title}
Chapter text:
---
{text}
---

Return only the summary text. No prose like "This chapter...". No quotes."""


async def summarize_chapter(title: str, text: str) -> str:
    if not text or not text.strip():
        return ""
    snippet = text.strip()[:8000]
    client = _get_client()
    try:
        resp = await asyncio.to_thread(
            client.models.generate_content,
            model="gemini-3.1-flash-lite",
            contents=PROMPT.replace("{title}", title or "").replace("{text}", snippet),
            config=types.GenerateContentConfig(
                temperature=0.4,
                max_output_tokens=200,
            ),
        )
        summary = (resp.text or "").strip()
        # Some models echo a leading "Summary:" — strip common boilerplate.
        for prefix in ("Summary:", "요약:", "Chapter summary:"):
            if summary.lower().startswith(prefix.lower()):
                summary = summary[len(prefix):].strip()
        return summary[:300]
    except Exception as exc:
        print(f"[summarizer] failed for {title!r}: {exc!r}")
        return ""


async def summarize_all_chapters(
    toc: list[dict],
    pages: list[str],
) -> dict[int, str]:
    """Returns {chapter_start_page: summary} for every TOC entry."""
    if not toc or not pages:
        return {}

    # Build chapter text by slicing pages between consecutive TOC starts.
    sorted_entries = sorted(toc, key=lambda e: int(e.get("page", 0)))
    chapters: list[tuple[int, str, str]] = []  # (page, title, text)
    for i, entry in enumerate(sorted_entries):
        start = int(entry.get("page", 0))
        if start < 1 or start > len(pages):
            continue
        end = (
            int(sorted_entries[i + 1].get("page", len(pages) + 1)) - 1
            if i + 1 < len(sorted_entries)
            else len(pages)
        )
        end = max(start, min(end, len(pages)))
        body = "\n\n".join(pages[start - 1 : end]).strip()
        chapters.append((start, str(entry.get("title", "")).strip(), body))

    sem = asyncio.Semaphore(4)

    async def bounded(start: int, title: str, body: str) -> tuple[int, str]:
        async with sem:
            s = await summarize_chapter(title, body)
            return start, s

    results = await asyncio.gather(
        *(bounded(p, t, b) for p, t, b in chapters)
    )
    return {p: s for p, s in results if s}
