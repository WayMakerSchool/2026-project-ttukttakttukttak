import os
from typing import AsyncIterator

from google import genai
from google.genai import types

# python.org macOS builds ship without system CA certs wired up; the Lyria
# websocket then dies with CERTIFICATE_VERIFY_FAILED on every connect.
# Point OpenSSL at certifi's bundle unless the env already provides one.
try:
    import certifi

    os.environ.setdefault("SSL_CERT_FILE", certifi.where())
except ImportError:
    pass

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

    async def set_prompt(
        self,
        prompt_text: str,
        bpm: int = 90,
        context_prompt: str | None = None,
        context_weight: float = 0.1,
    ) -> None:
        if self._session is None:
            raise RuntimeError("Lyria session not started")
        bpm = max(40, min(160, int(bpm)))
        prompts = [types.WeightedPrompt(text=prompt_text, weight=1.0)]
        if context_prompt and context_prompt.strip():
            # Reading-environment tint (place/weather/season). Lyria normalizes
            # weights, so book 0.9 : context 0.1 means the environment nudges the
            # music ~10% while the book's own mood stays dominant.
            cw = max(0.0, min(0.5, context_weight))
            prompts = [
                types.WeightedPrompt(text=prompt_text, weight=1.0 - cw),
                types.WeightedPrompt(text=context_prompt.strip(), weight=cw),
            ]
        await self._session.set_weighted_prompts(prompts=prompts)
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
