import asyncio
import json

from fastapi import WebSocket, WebSocketDisconnect

from audio_cache import (
    BYTES_PER_SECOND,
    CHANNELS,
    SAMPLE_RATE,
    BYTES_PER_SAMPLE,
    mood_to_segment_index,
    segment_path,
    segments_ready,
    unique_mood_segments,
)

# How big each WebSocket audio frame is. ~85 ms keeps the client's audio queue
# small and lets a mood switch take effect quickly without micro-stutter.
CHUNK_FRAMES = 4096
CHUNK_BYTES = CHUNK_FRAMES * CHANNELS * BYTES_PER_SAMPLE
CHUNK_DURATION_S = CHUNK_FRAMES / SAMPLE_RATE


class MusicSession:
    """Streams cached per-mood PCM segments to the client.

    No Lyria call happens during playback — segments are written once by
    `audio_cache.generate_all_segments` at upload time.
    """

    def __init__(self, book_id: str, moods: list[dict]):
        if not moods:
            raise ValueError("moods must be non-empty")
        self.book_id = book_id
        self.moods = moods
        self.segments = unique_mood_segments(moods)
        self.current_idx = mood_to_segment_index(moods[0], self.segments)
        self._stop = asyncio.Event()

    async def run(self, ws: WebSocket) -> None:
        if not segments_ready(self.book_id, self.segments):
            await ws.send_text(
                json.dumps(
                    {
                        "type": "error",
                        "message": "이 책의 음악이 아직 준비 중이에요. 잠시 후 다시 시도해주세요.",
                    }
                )
            )
            return

        sender = asyncio.create_task(self._send_audio(ws))
        receiver = asyncio.create_task(self._recv_control(ws))
        try:
            done, pending = await asyncio.wait(
                [sender, receiver], return_when=asyncio.FIRST_COMPLETED
            )
            for task in pending:
                task.cancel()
            for task in done:
                exc = task.exception()
                if exc and not isinstance(exc, WebSocketDisconnect):
                    print(f"[ws_handler] task error: {exc!r}")
        finally:
            self._stop.set()

    async def _send_audio(self, ws: WebSocket) -> None:
        """Stream the active segment, looping. Re-checks `current_idx` between
        chunks so a page-change can take effect mid-stream."""
        while not self._stop.is_set():
            idx = self.current_idx
            path = segment_path(self.book_id, idx)
            try:
                data = path.read_bytes()
            except FileNotFoundError:
                await asyncio.sleep(0.1)
                continue
            if not data:
                await asyncio.sleep(0.1)
                continue

            offset = 0
            while offset < len(data) and not self._stop.is_set():
                if self.current_idx != idx:
                    break  # mood switched; restart outer loop with new segment
                chunk = data[offset : offset + CHUNK_BYTES]
                offset += len(chunk)
                try:
                    await ws.send_bytes(chunk)
                except WebSocketDisconnect:
                    self._stop.set()
                    return
                await asyncio.sleep(CHUNK_DURATION_S)
            # If we hit end of file without an interruption, loop the segment.

    async def _recv_control(self, ws: WebSocket) -> None:
        while not self._stop.is_set():
            try:
                text = await ws.receive_text()
            except WebSocketDisconnect:
                self._stop.set()
                return
            try:
                msg = json.loads(text)
            except json.JSONDecodeError:
                continue
            if msg.get("type") == "page":
                page = int(msg.get("page", 0))
                if 0 <= page < len(self.moods):
                    new_idx = mood_to_segment_index(self.moods[page], self.segments)
                    if new_idx != self.current_idx:
                        self.current_idx = new_idx
