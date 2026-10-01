"""Gemini-powered character + relationship extractor.

We feed up to ~80k characters of the book (page-prefixed) into Flash-Lite
and ask for a compact JSON of the main cast plus the most defining
relationships. Cost: roughly $0.01 per book. Result cached in DB.
"""

import asyncio
import json
import os

from google import genai
from google.genai import types

_client: genai.Client | None = None

MODEL = "gemini-3.1-flash-lite"
MAX_INPUT_CHARS = 80000  # Korean text ~ 1 token / char, well under context window.


def _get_client() -> genai.Client:
    global _client
    if _client is None:
        api_key = os.environ.get("GEMINI_API_KEY")
        if not api_key:
            raise RuntimeError("GEMINI_API_KEY is not set")
        _client = genai.Client(api_key=api_key)
    return _client


PROMPT = """Analyze this book and extract the main characters and their key
relationships.

Return JSON with this exact shape:
{
  "characters": [
    {
      "name": "character name as written",
      "description": "1 sentence — who they are in this story",
      "first_page": int (page number they first clearly appear),
      "importance": int 1-5 (5 = protagonist, 1 = minor)
    }
  ],
  "relationships": [
    {
      "from": "name1",
      "to": "name2",
      "label": "one short word/phrase: 친구/적/연인/가족/동료/스승/제자/라이벌/...",
      "kind": "friend|enemy|romance|family|colleague|mentor|rival|other"
    }
  ]
}

Strict rules:
- Maximum 12 characters. Only the meaningful cast — skip walk-ons.
- Reply in the SAME LANGUAGE as the book (Korean book → Korean names + Korean descriptions).
- Names must match how they appear in the text (don't invent).
- `first_page` must be a page number from the excerpt below.
- Skip relationships that aren't clearly established in the text.
- If this is non-fiction with no characters, return both arrays empty.

Book excerpt (page-prefixed):
{pages}

Return only the JSON object — no prose, no markdown fences."""


async def extract_characters(pages: list[str]) -> dict:
    if not pages:
        return {"characters": [], "relationships": []}

    parts: list[str] = []
    used = 0
    for i, raw in enumerate(pages, start=1):
        snippet = (raw or "").strip().replace("\n", " ")
        if not snippet:
            continue
        line = f"[p.{i}] {snippet}"
        if used + len(line) > MAX_INPUT_CHARS:
            remaining = MAX_INPUT_CHARS - used
            if remaining > 200:
                parts.append(line[:remaining])
            break
        parts.append(line)
        used += len(line) + 1
    if not parts:
        return {"characters": [], "relationships": []}

    pages_block = "\n".join(parts)
    client = _get_client()
    try:
        resp = await asyncio.to_thread(
            client.models.generate_content,
            model=MODEL,
            contents=PROMPT.replace("{pages}", pages_block),
            config=types.GenerateContentConfig(
                response_mime_type="application/json",
                temperature=0.3,
                max_output_tokens=4000,
            ),
        )
        raw = json.loads(resp.text or "{}")
    except Exception as exc:
        print(f"[characters] failed: {exc!r}")
        return {"characters": [], "relationships": []}

    chars = []
    seen_names: set[str] = set()
    for c in (raw.get("characters") or [])[:12]:
        if not isinstance(c, dict):
            continue
        name = str(c.get("name", "")).strip()
        if not name or name in seen_names:
            continue
        try:
            chars.append(
                {
                    "name": name,
                    "description": str(c.get("description", "")).strip()[:200],
                    "first_page": max(1, int(c.get("first_page", 1))),
                    "importance": max(1, min(5, int(c.get("importance", 3)))),
                }
            )
            seen_names.add(name)
        except (ValueError, TypeError):
            continue

    rels = []
    for r in (raw.get("relationships") or [])[:30]:
        if not isinstance(r, dict):
            continue
        a = str(r.get("from", "")).strip()
        b = str(r.get("to", "")).strip()
        if not a or not b or a == b:
            continue
        if a not in seen_names or b not in seen_names:
            continue
        rels.append(
            {
                "from": a,
                "to": b,
                "label": str(r.get("label", "")).strip()[:30] or "관계",
                "kind": str(r.get("kind", "other")).strip().lower()[:20] or "other",
            }
        )

    chars.sort(key=lambda c: -c["importance"])
    return {"characters": chars, "relationships": rels}
