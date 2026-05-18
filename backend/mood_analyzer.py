import asyncio
import json
import os

from google import genai
from google.genai import types

_client: genai.Client | None = None

DEFAULT_MOOD = {
    "instruments": ["ambient pad"],
    "genre": "ambient",
    "bpm": 60,
    "mood": "neutral",
    "intensity": 0.2,
    "prompt": "soft ambient pad, gentle drone",
}

MOOD_PROMPT = """You analyze a single page of a book and produce a JSON object describing the background music that would fit the page's emotional tone.

Return JSON with this exact schema:
{
  "instruments": [string],  // 1-4 instruments, e.g. ["piano", "strings"]
  "genre": string,          // e.g. "ambient", "orchestral", "lo-fi", "cinematic"
  "bpm": number,            // integer between 40 and 160
  "mood": string,           // one short word: "calm", "tense", "melancholic", "joyful", "mysterious", "epic", "romantic", ...
  "intensity": number,      // 0.0 (silent/sparse) to 1.0 (full, loud)
  "prompt": string          // ONE concise English line suitable as a music-generation prompt for Lyria
}

Page text:
---
{text}
---

Return only the JSON object. No prose, no markdown."""


def _get_client() -> genai.Client:
    global _client
    if _client is None:
        api_key = os.environ.get("GEMINI_API_KEY")
        if not api_key:
            raise RuntimeError("GEMINI_API_KEY environment variable is not set")
        _client = genai.Client(api_key=api_key)
    return _client


def _parse_mood(raw: str) -> dict:
    try:
        data = json.loads(raw)
        if not isinstance(data, dict) or "prompt" not in data:
            return DEFAULT_MOOD
        return data
    except (json.JSONDecodeError, TypeError):
        return DEFAULT_MOOD


async def analyze_page(text: str) -> dict:
    if not text or not text.strip():
        return DEFAULT_MOOD
    client = _get_client()
    prompt = MOOD_PROMPT.replace("{text}", text[:4000])
    try:
        resp = await asyncio.to_thread(
            client.models.generate_content,
            model="gemini-3.1-flash-lite",
            contents=prompt,
            config=types.GenerateContentConfig(response_mime_type="application/json"),
        )
        return _parse_mood(resp.text or "")
    except Exception as exc:
        print(f"[mood_analyzer] page analysis failed: {exc}")
        return DEFAULT_MOOD


async def analyze_pages(pages: list[str]) -> list[dict]:
    sem = asyncio.Semaphore(8)

    async def bounded(text: str) -> dict:
        async with sem:
            return await analyze_page(text)

    return await asyncio.gather(*(bounded(p) for p in pages))
