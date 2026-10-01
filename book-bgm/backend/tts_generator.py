"""Gemini TTS — narrates a single page into PCM audio.

We wrap the raw PCM in a WAV header so the browser can play it directly via
`<audio src>` or `AudioContext.decodeAudioData` without us shipping format
metadata over the wire.
"""

import asyncio
import os
import struct

from google import genai
from google.genai import types

# Gemini TTS preview models. Flash is the cheaper/faster path.
TTS_MODEL = "gemini-2.5-flash-preview-tts"

# Pre-built voices Google ships. "Charon" handles Korean reasonably.
DEFAULT_VOICE = "Charon"

# Gemini TTS outputs 24 kHz mono signed-16-bit PCM.
TTS_SAMPLE_RATE = 24000
TTS_CHANNELS = 1
TTS_BITS = 16

# Cap per-page input so a single page can't blow up tokens.
MAX_TTS_CHARS = 4500

_client: genai.Client | None = None


def _get_client() -> genai.Client:
    global _client
    if _client is None:
        api_key = os.environ.get("GEMINI_API_KEY")
        if not api_key:
            raise RuntimeError("GEMINI_API_KEY is not set")
        _client = genai.Client(api_key=api_key)
    return _client


def _extract_audio_bytes(response) -> bytes:
    candidates = getattr(response, "candidates", None) or []
    for cand in candidates:
        content = getattr(cand, "content", None)
        if not content:
            continue
        for part in getattr(content, "parts", None) or []:
            inline = getattr(part, "inline_data", None)
            if inline and getattr(inline, "data", None):
                return inline.data
    return b""


def pcm_to_wav(
    pcm: bytes,
    sample_rate: int = TTS_SAMPLE_RATE,
    channels: int = TTS_CHANNELS,
    bits: int = TTS_BITS,
) -> bytes:
    byte_rate = sample_rate * channels * bits // 8
    block_align = channels * bits // 8
    data_size = len(pcm)
    return (
        b"RIFF"
        + struct.pack("<I", 36 + data_size)
        + b"WAVE"
        + b"fmt "
        + struct.pack("<IHHIIHH", 16, 1, channels, sample_rate, byte_rate, block_align, bits)
        + b"data"
        + struct.pack("<I", data_size)
        + pcm
    )


async def generate_page_tts(text: str, voice: str = DEFAULT_VOICE) -> bytes:
    """Generate WAV audio for a single page's text."""
    trimmed = (text or "").strip()
    if not trimmed:
        return b""
    client = _get_client()
    resp = await asyncio.to_thread(
        client.models.generate_content,
        model=TTS_MODEL,
        contents=trimmed[:MAX_TTS_CHARS],
        config=types.GenerateContentConfig(
            response_modalities=["AUDIO"],
            speech_config=types.SpeechConfig(
                voice_config=types.VoiceConfig(
                    prebuilt_voice_config=types.PrebuiltVoiceConfig(voice_name=voice)
                )
            ),
        ),
    )
    pcm = _extract_audio_bytes(resp)
    if not pcm:
        return b""
    return pcm_to_wav(pcm)
