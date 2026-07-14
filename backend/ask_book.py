"""책에게 묻기 — Gemini-powered Q&A grounded in the book's text.

Strategy: build a compact context (title, description, TOC, chapter
summaries, ±5 pages around the user's current page) and append the recent
chat history. Gemini answers in the same language as the question.
"""

import asyncio
import os
from typing import Iterable

from google import genai
from google.genai import types

_client: genai.Client | None = None

MODEL = "gemini-3.1-flash-lite"
NEARBY_PAGES = 5
MAX_PAGE_SNIPPET = 1200


def _get_client() -> genai.Client:
    global _client
    if _client is None:
        api_key = os.environ.get("GEMINI_API_KEY")
        if not api_key:
            raise RuntimeError("GEMINI_API_KEY is not set")
        _client = genai.Client(api_key=api_key)
    return _client


def _build_context(
    title: str,
    description: str | None,
    toc: list[dict] | None,
    summaries: dict[int, str] | None,
    pages: list[str],
    current_page: int,
) -> str:
    lines: list[str] = []
    lines.append(f"BOOK TITLE: {title}")
    if description:
        lines.append(f"DESCRIPTION: {description.strip()[:600]}")

    # Spoiler boundary — never reveal anything from chapters the reader hasn't reached yet.
    if toc:
        lines.append("")
        lines.append(
            f"TABLE OF CONTENTS (only chapters up to page {current_page} — "
            "reader has NOT read beyond this point):"
        )
        hidden = 0
        for entry in toc[:60]:
            page = entry.get("page", "?")
            try:
                page_num = int(page)
            except (TypeError, ValueError):
                page_num = 0
            if page_num > current_page:
                hidden += 1
                continue
            summary = (summaries or {}).get(page_num)
            base = f"  p.{page}: {entry.get('title', '').strip()}"
            if summary:
                base += f" — {summary.strip()[:140]}"
            lines.append(base)
        if hidden > 0:
            lines.append(f"  […{hidden} more chapters hidden to avoid spoilers]")

    if pages:
        start = max(1, current_page - NEARBY_PAGES)
        # Hard spoiler boundary — never include pages the reader hasn't reached.
        end = min(len(pages), current_page)
        lines.append("")
        lines.append(
            f"NEARBY EXCERPTS (the reader is currently on page {current_page}; "
            f"showing pages {start}-{end} — the reader has NOT read past page {current_page}):"
        )
        for p in range(start, end + 1):
            snippet = (pages[p - 1] or "").strip().replace("\n", " ")[:MAX_PAGE_SNIPPET]
            marker = " ← CURRENT PAGE" if p == current_page else ""
            lines.append(f"[p.{p}{marker}] {snippet}")

    return "\n".join(lines)


def _build_history(history: Iterable[dict]) -> str:
    out: list[str] = []
    for m in list(history)[-8:]:  # last 8 turns
        role = "USER" if m.get("role") == "user" else "ASSISTANT"
        content = str(m.get("content", "")).strip()
        if content:
            out.append(f"{role}: {content}")
    return "\n".join(out)


SYSTEM = """You are a thoughtful reading companion. The user is reading a
book and asks you questions about it. Use the book context below to answer.

Rules:
- Reply in the SAME LANGUAGE as the user's question (Korean question → Korean answer).
- Be brief and warm. 2-5 sentences usually.
- Ground your answer in the provided context. If the answer is not in the
  excerpts or TOC, say so honestly ("아직 그 부분은 보이지 않아요") rather than
  inventing details.
- ABSOLUTE SPOILER RULE: Never reveal, summarize, hint at, or compare against
  anything past the reader's current page. Even if you happen to know the book
  from training data, treat everything beyond the current page as unknown. If
  the user asks about future chapters or "what happens next," reply gently
  with "아직 거기까지 읽지 않으셨어요 — 읽으시면서 같이 보면 좋을 것 같아요."
- "Summarize so far" requests are fine and should ONLY cover up to the
  current page.
- Don't fabricate page numbers."""


async def ask_book(
    *,
    title: str,
    description: str | None,
    toc: list[dict] | None,
    summaries: dict[int, str] | None,
    pages: list[str],
    current_page: int,
    history: list[dict],
    question: str,
) -> str:
    if not question.strip():
        return ""
    context = _build_context(title, description, toc, summaries, pages, current_page)
    convo = _build_history(history)
    user_block = (
        f"{context}\n\n"
        + (f"PREVIOUS CONVERSATION:\n{convo}\n\n" if convo else "")
        + f"USER QUESTION: {question.strip()}"
    )

    client = _get_client()
    resp = await asyncio.to_thread(
        client.models.generate_content,
        model=MODEL,
        contents=user_block,
        config=types.GenerateContentConfig(
            system_instruction=SYSTEM,
            temperature=0.4,
            max_output_tokens=600,
        ),
    )
    return (resp.text or "").strip()
