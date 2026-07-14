"""Generate a book cover image via Nano Banana (gemini-2.5-flash-image).

Different from chapter_image.py: prompts for a vertical *book cover* —
no text rendering, atmospheric, square-to-portrait aspect."""

import asyncio
import os

from google import genai
from google.genai import types

NANO_BANANA = "gemini-2.5-flash-image"

_client: genai.Client | None = None


def _get_client() -> genai.Client:
    global _client
    if _client is None:
        api_key = os.environ.get("GEMINI_API_KEY")
        if not api_key:
            raise RuntimeError("GEMINI_API_KEY is not set")
        _client = genai.Client(api_key=api_key)
    return _client


def _build_prompt(title: str, description: str, category: str) -> str:
    title = (title or "").strip()
    description = (description or "").strip()[:1500]
    category = (category or "").strip()
    parts = [
        "Design a vertical book cover illustration in portrait (2:3) aspect ratio.",
    ]
    if title:
        parts.append(f'Book title: "{title}"')
    if category:
        parts.append(f"Category: {category}")
    if description:
        parts.append(f"Description: {description}")
    parts.append(
        "Style: painterly, evocative, atmospheric — like a modern literary fiction "
        "cover. Strong central focal point, mood-driven color palette, soft "
        "gradients, leave breathing space at the top and bottom for the title "
        "(but DO NOT render any text, captions, logos, or characters). "
        "Render the imagery only — typography will be added later."
    )
    return "\n\n".join(parts)


def _extract_png(response) -> bytes | None:
    for cand in getattr(response, "candidates", None) or []:
        content = getattr(cand, "content", None)
        if not content:
            continue
        for part in getattr(content, "parts", None) or []:
            inline = getattr(part, "inline_data", None)
            if inline and getattr(inline, "data", None):
                return inline.data
    return None


def _generate_sync(prompt: str) -> bytes:
    client = _get_client()
    resp = client.models.generate_content(
        model=NANO_BANANA,
        contents=prompt,
        config=types.GenerateContentConfig(response_modalities=["IMAGE", "TEXT"]),
    )
    data = _extract_png(resp)
    if not data:
        raise RuntimeError("Nano Banana returned no image data")
    return data


async def generate_cover(title: str, description: str = "", category: str = "") -> bytes:
    """Return PNG bytes for a generated cover."""
    prompt = _build_prompt(title, description, category)
    return await asyncio.to_thread(_generate_sync, prompt)
