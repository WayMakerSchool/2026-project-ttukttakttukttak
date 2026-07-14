"""Per-book visual style sheet.

Generated once per book and reused for every chapter image so the
illustrations look like they belong to the same volume — same art style,
palette, character looks, lighting, world. Without this, each Nano Banana
call invents fresh visuals and the gallery looks like a stock-photo collage.

Output is a small JSON object cached on the book row (image_style_json):
    {
      "art_style":   "...",        # how to draw (medium, brushwork, era)
      "palette":     [...],        # 3-5 anchor colors
      "lighting":    "...",
      "world":       "...",        # setting, era, atmosphere
      "mood":        "...",        # baseline tone
      "characters":  [             # consistent looks for the main cast
        {"name": "...", "look": "..."},
        ...
      ]
    }
"""

import asyncio
import json
import os
import re
from typing import Any

from google import genai
from google.genai import types

_client: genai.Client | None = None

MODEL = "gemini-3.1-flash-lite"


def _get_client() -> genai.Client:
    global _client
    if _client is None:
        api_key = os.environ.get("GEMINI_API_KEY")
        if not api_key:
            raise RuntimeError("GEMINI_API_KEY is not set")
        _client = genai.Client(api_key=api_key)
    return _client


SYSTEM = """You are an art director designing a unified illustration look
for an entire book — every chapter image will reference this style sheet,
so it must be specific, concrete, and visually unambiguous.

Return STRICT JSON only (no prose, no markdown fences):
{
  "art_style":  "1-2 sentences describing medium + technique + era (e.g.
                 'soft watercolor, painterly brush textures, 1950s
                  illustrated-novel aesthetic')",
  "palette":    ["color1", "color2", "color3", "color4"]  // 3-5 muted
                                                          // anchor tones
  "lighting":   "1 sentence on light direction + temperature",
  "world":      "1-2 sentences on the setting, era, geography",
  "mood":       "single phrase capturing the emotional tone",
  "characters": [
    { "name": "이름", "look": "concrete physical description —
                              age, hair, clothing, distinguishing feature.
                              Must be specific enough that two illustrators
                              would draw the SAME character." },
    ...  // include up to 6 most important characters
  ]
}

Rules:
- All description strings can be in English or Korean — pick whichever
  language the character names are in.
- Lock down concrete visual cues. Avoid vague words like 'mysterious',
  'beautiful'. Prefer 'dark almond eyes, ink-black bobbed hair, navy hanbok'.
- Be plausible given the book's genre, setting, and era."""


def _strip_json(raw: str) -> str:
    s = raw.strip()
    if s.startswith("```"):
        s = re.sub(r"^```[a-zA-Z]*\n?", "", s)
        s = re.sub(r"\n?```$", "", s)
    return s.strip()


def _build_input(
    *,
    title: str,
    description: str | None,
    category: str | None,
    language: str | None,
    published_year: int | None,
    characters: list[dict] | None,
    sample_text: str,
) -> str:
    lines: list[str] = [f"BOOK TITLE: {title}"]
    if category:
        lines.append(f"CATEGORY: {category}")
    if language:
        lines.append(f"LANGUAGE: {language}")
    if published_year:
        lines.append(f"PUBLISHED: {published_year}")
    if description:
        lines.append(f"DESCRIPTION: {description.strip()[:600]}")
    if characters:
        cast = []
        for c in (characters or [])[:8]:
            name = (c.get("name") or "").strip()
            desc = (c.get("description") or "").strip()
            if name:
                cast.append(f"- {name}: {desc}")
        if cast:
            lines.append("CHARACTERS:\n" + "\n".join(cast))
    if sample_text:
        lines.append("SAMPLE OPENING:\n" + sample_text.strip()[:1500])
    return "\n\n".join(lines)


async def design_style(
    *,
    title: str,
    description: str | None = None,
    category: str | None = None,
    language: str | None = None,
    published_year: int | None = None,
    characters: list[dict] | None = None,
    sample_text: str = "",
) -> dict[str, Any]:
    prompt = _build_input(
        title=title,
        description=description,
        category=category,
        language=language,
        published_year=published_year,
        characters=characters,
        sample_text=sample_text,
    )
    client = _get_client()
    resp = await asyncio.to_thread(
        client.models.generate_content,
        model=MODEL,
        contents=prompt,
        config=types.GenerateContentConfig(
            system_instruction=SYSTEM,
            temperature=0.6,
            max_output_tokens=900,
            response_mime_type="application/json",
        ),
    )
    raw = (resp.text or "").strip()
    try:
        data = json.loads(_strip_json(raw))
    except json.JSONDecodeError:
        # Fallback to a generic but coherent style if the model returns junk.
        data = {
            "art_style": "Soft painterly illustration, cinematic composition, "
            "muted brush textures",
            "palette": ["warm sepia", "muted teal", "cream", "deep umber"],
            "lighting": "Diffuse golden-hour light, low contrast",
            "world": (description or "").strip()[:200] or "",
            "mood": "Quiet, contemplative",
            "characters": [],
        }
    data.setdefault("art_style", "")
    data.setdefault("palette", [])
    data.setdefault("lighting", "")
    data.setdefault("world", "")
    data.setdefault("mood", "")
    data.setdefault("characters", [])
    return data


def style_to_prompt_block(style: dict[str, Any]) -> str:
    """Render the style sheet as a stable header that prefixes every
    chapter prompt. Phrased as hard constraints so the image model
    treats it as global state, not optional flavor."""
    parts: list[str] = []
    parts.append(
        "VISUAL STYLE SHEET (must be IDENTICAL across every chapter of "
        "this book — treat as hard constraints, do not reinterpret):"
    )
    if style.get("art_style"):
        parts.append(f"Art style: {style['art_style']}")
    pal = style.get("palette") or []
    if pal:
        parts.append(f"Color palette (anchor tones, use these only): {', '.join(pal)}")
    if style.get("lighting"):
        parts.append(f"Lighting: {style['lighting']}")
    if style.get("world"):
        parts.append(f"World / setting: {style['world']}")
    if style.get("mood"):
        parts.append(f"Mood baseline: {style['mood']}")
    chars = style.get("characters") or []
    if chars:
        lines = ["Characters (render with these EXACT looks every time):"]
        for c in chars[:6]:
            name = (c.get("name") or "").strip()
            look = (c.get("look") or "").strip()
            if name and look:
                lines.append(f"  - {name}: {look}")
        parts.append("\n".join(lines))
    return "\n".join(parts)
