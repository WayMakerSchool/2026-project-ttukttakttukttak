import os
from typing import AsyncIterator

from google import genai
from google.genai import types

LYRIA_MODEL = "models/lyria-realtime-exp"


class LyriaStream:
    """Async context manager wrapping a Lyria RealTime session.

    Yields raw PCM audio chunks (48 kHz, stereo, signed 16-bit little-endian).
    """

    def __init__(self) -> None:
        api_key = os.environ.get("GEMINI_API_KEY")
        if not api_key:
            raise RuntimeError("GEMINI_API_KEY environment variable is not set")
        self._client = genai.Client(
            api_key=api_key, http_options={"api_version": "v1alpha"}
        )
        self._ctx = None
        self._session = None

    async def __aenter__(self) -> "LyriaStream":
        self._ctx = self._client.aio.live.music.connect(model=LYRIA_MODEL)
        self._session = await self._ctx.__aenter__()
        return self

    async def __aexit__(self, exc_type, exc, tb) -> None:
        if self._ctx is not None:
            await self._ctx.__aexit__(exc_type, exc, tb)
            self._ctx = None
            self._session = None

    async def set_prompt(self, prompt_text: str, bpm: int = 90) -> None:
        if self._session is None:
            raise RuntimeError("Lyria session not started")
        bpm = max(40, min(160, int(bpm)))
        await self._session.set_weighted_prompts(
            prompts=[types.WeightedPrompt(text=prompt_text, weight=1.0)]
        )
        await self._session.set_music_generation_config(
            config=types.LiveMusicGenerationConfig(bpm=bpm)
        )

    async def play(self) -> None:
        if self._session is None:
            raise RuntimeError("Lyria session not started")
        await self._session.play()

    async def stop(self) -> None:
        if self._session is None:
            return
        try:
            await self._session.stop()
        except Exception:
            pass

    async def audio_chunks(self) -> AsyncIterator[bytes]:
        if self._session is None:
            raise RuntimeError("Lyria session not started")
        async for msg in self._session.receive():
            content = getattr(msg, "server_content", None)
            if not content:
                continue
            chunks = getattr(content, "audio_chunks", None) or []
            for chunk in chunks:
                data = getattr(chunk, "data", None)
                if data:
                    yield data
