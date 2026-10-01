"""Gemini-based chapter illustration generator.

Given a chapter's title + summary + opening text, asks Gemini to produce a
single atmospheric illustration that matches the scene. Result is PNG bytes;
the caller is responsible for caching to disk (one image per book/page).

Tries several model names in order because Google has renamed the image-gen
model a few times and accounts have different access tiers."""

import asyncio
import os

from google import genai
from google.genai import types

# "Nano Banana" — Google's codename for gemini-2.5-flash-image. This is the
# only model we use. (Imagen-4 fallback kept ONLY for hard outages of the
# Nano Banana endpoint; tried last.)
MODEL_CANDIDATES: list[tuple[str, str]] = [
    ("gemini-2.5-flash-image", "gemini"),  # Nano Banana
    ("imagen-4.0-fast-generate-001", "imagen"),  # emergency fallback
]

_client: genai.Client | None = None


def _get_client() -> genai.Client:
    global _client
    if _client is None:
        api_key = os.environ.get("GEMINI_API_KEY")
        if not api_key:
            raise RuntimeError("GEMINI_API_KEY is not set")
        _client = genai.Client(api_key=api_key)
    return _client


def _build_prompt(
    title: str,
    summary: str,
    excerpt: str,
    style_block: str = "",
) -> str:
    title = (title or "").strip()
    summary = (summary or "").strip()
    excerpt = (excerpt or "").strip()[:1200]
    parts: list[str] = []
    # The style sheet goes FIRST and is phrased as hard global constraints
    # so the image model treats it as state for the whole book, not flavor
    # for one scene. Without this header each chapter invents its own look.
    if style_block:
        parts.append(style_block)
    parts.append(
        "A single atmospheric illustration of the most defining moment of "
        "this chapter — not a literal depiction of the chapter title."
    )
    if title:
        parts.append(f'Chapter title (label only, do NOT illustrate the title text): "{title}"')
    if summary:
        parts.append(f"Chapter summary: {summary}")
    if excerpt:
        parts.append(
            "Chapter text (illustrate one of the most visually striking scenes from this):\n"
            + excerpt
        )
    parts.append(
        "Pick ONE pivotal visual moment from the chapter text above and "
        "render it — the people present, what they're doing, where they "
        "are, the weather and time of day implied by the text. Use the "
        "EXACT visual style, palette, lighting, character looks, and "
        "world defined in the style sheet so this image looks like it "
        "belongs to the same book as every other chapter's illustration. "
        "No text, no captions, no logos. Wide landscape orientation."
    )
    return "\n\n".join(parts)


def _extract_image_bytes_from_content(response) -> bytes | None:
    for cand in getattr(response, "candidates", None) or []:
        content = getattr(cand, "content", None)
        if not content:
            continue
        for part in getattr(content, "parts", None) or []:
            inline = getattr(part, "inline_data", None)
            if inline and getattr(inline, "data", None):
                return inline.data
    return None


def _extract_image_bytes_from_images(response) -> bytes | None:
    imgs = getattr(response, "generated_images", None) or []
    for gi in imgs:
        img = getattr(gi, "image", None)
        data = getattr(img, "image_bytes", None) if img else None
        if data:
            return data
    return None


def _call_gemini_image_model(model: str, prompt: str) -> bytes | None:
    client = _get_client()
    resp = client.models.generate_content(
        model=model,
        contents=prompt,
        config=types.GenerateContentConfig(
            response_modalities=["IMAGE", "TEXT"],
        ),
    )
    return _extract_image_bytes_from_content(resp)


def _call_imagen_model(model: str, prompt: str) -> bytes | None:
    client = _get_client()
    resp = client.models.generate_images(
        model=model,
        prompt=prompt,
        config=types.GenerateImagesConfig(
            number_of_images=1,
            aspect_ratio="16:9",
        ),
    )
    return _extract_image_bytes_from_images(resp)


def _generate_sync(prompt: str) -> bytes:
    last_err: Exception | None = None
    for model, kind in MODEL_CANDIDATES:
        try:
            if kind == "imagen":
                data = _call_imagen_model(model, prompt)
            else:
                data = _call_gemini_image_model(model, prompt)
            if data:
                print(f"[chapter_image] generated via {model}")
                return data
            last_err = RuntimeError(f"{model} returned no image data")
        except Exception as exc:
            last_err = exc
            print(f"[chapter_image] {model} failed: {exc!r}")
            continue
    raise RuntimeError(
        f"All image models failed. Last error: {last_err!r}. "
        "Check your Gemini API key access tier — image generation may require "
        "a paid project."
    )


async def generate_chapter_image(
    title: str,
    summary: str,
    excerpt: str,
    style_block: str = "",
) -> bytes:
    """Returns PNG bytes for the chapter, or raises on failure.

    `style_block` is the rendered string from `style_designer.style_to_prompt_block`.
    Pass the SAME block for every chapter of a book so the illustrations
    feel like they belong to one volume."""
    prompt = _build_prompt(title, summary, excerpt, style_block)
    return await asyncio.to_thread(_generate_sync, prompt)
