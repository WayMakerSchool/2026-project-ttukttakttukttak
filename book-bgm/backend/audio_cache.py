"""Per-book audio cache.

Once a book is uploaded and Gemini has produced per-page moods, we walk through
the unique mood prompts and have Lyria generate a short PCM segment for each.
Those segments are saved to disk; subsequent playbacks (this user, other users,
later sessions) stream from the cache instead of burning Lyria tokens.
"""

import asyncio
from pathlib import Path

from music_streamer import LyriaStream

# Lyria RealTime output format.
SAMPLE_RATE = 48000
CHANNELS = 2
BYTES_PER_SAMPLE = 2  # int16
BYTES_PER_SECOND = SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE

# Per-mood capture length. Each segment is later looped during playback.
SEGMENT_DURATION_S = 20

# Hard cap on Lyria sessions per book. Gemini will hand back a freeform prompt
# for every page (often slightly different even for similar scenes), so we
# bucket aggressively. Anything beyond this gets aliased to the nearest segment
# by BPM at lookup time.
MAX_SEGMENTS = 12

AUDIO_ROOT = Path(__file__).parent / "storage" / "audio"
AUDIO_ROOT.mkdir(parents=True, exist_ok=True)


def book_audio_dir(book_id: str) -> Path:
    d = AUDIO_ROOT / book_id
    d.mkdir(parents=True, exist_ok=True)
    return d


def segment_path(book_id: str, idx: int) -> Path:
    return book_audio_dir(book_id) / f"seg_{idx}.pcm"


def _bucket_key(mood: dict) -> str:
    """Coarse bucket so '70 BPM somber cinematic' and '75 BPM somber
    cinematic' collapse to the same Lyria segment. Bucket by
    (mood-word, genre, bpm rounded to nearest 20)."""
    m = (mood.get("mood") or "neutral").strip().lower()
    g = (mood.get("genre") or "ambient").strip().lower()
    bpm = int(mood.get("bpm") or 80)
    bpm_bucket = (bpm // 20) * 20
    return f"{m}|{g}|{bpm_bucket}"


def unique_mood_segments(moods: list[dict]) -> list[dict]:
    """Group moods into a small set of buckets (capped at MAX_SEGMENTS).
    Pages whose bucket would exceed the cap are aliased at lookup time
    to the nearest existing segment by BPM."""
    seen: dict[str, int] = {}
    segs: list[dict] = []
    for mood in moods:
        key = _bucket_key(mood)
        if key in seen:
            continue
        if len(segs) >= MAX_SEGMENTS:
            continue  # will fall through to bpm-nearest at lookup
        idx = len(segs)
        seen[key] = idx
        segs.append(
            {
                "idx": idx,
                "prompt": (mood.get("prompt") or "soft ambient pad").strip(),
                "bpm": int(mood.get("bpm") or 80),
                "mood": (mood.get("mood") or "neutral").strip(),
                "genre": (mood.get("genre") or "ambient").strip(),
                "key": key,
            }
        )
    return segs


def mood_to_segment_index(mood: dict | None, segments: list[dict]) -> int:
    if not mood or not segments:
        return 0
    key = _bucket_key(mood)
    for s in segments:
        if s["key"] == key:
            return s["idx"]
    # Fallback: nearest by BPM. Handles overflow buckets and stale segment
    # files written under a previous keying scheme.
    bpm = int(mood.get("bpm") or 80)
    nearest = min(segments, key=lambda s: abs(int(s.get("bpm", 80)) - bpm))
    return int(nearest["idx"])


async def _capture_segment(book_id: str, segment: dict, duration_s: int = SEGMENT_DURATION_S) -> None:
    """Open one Lyria session and capture `duration_s` seconds of PCM."""
    path = segment_path(book_id, segment["idx"])
    target_bytes = BYTES_PER_SECOND * duration_s
    if path.exists() and path.stat().st_size >= target_bytes:
        return  # already cached

    async with LyriaStream() as stream:
        await stream.set_prompt(segment["prompt"], segment["bpm"])
        await stream.play()
        captured = bytearray()
        try:
            async with asyncio.timeout(duration_s + 30):
                async for chunk in stream.audio_chunks():
                    captured.extend(chunk)
                    if len(captured) >= target_bytes:
                        break
        except asyncio.TimeoutError:
            pass
        try:
            await stream.stop()
        except Exception:
            pass

    if len(captured) < BYTES_PER_SECOND * 2:
        raise RuntimeError(
            f"Lyria returned only {len(captured)} bytes for prompt={segment['prompt']!r}"
        )
    # Trim to exact target so the file is always SAMPLE_RATE-aligned.
    path.write_bytes(bytes(captured[:target_bytes]))


async def generate_all_segments(book_id: str, moods: list[dict]) -> list[dict]:
    """Generate one PCM segment per unique mood. Returns segment metadata
    augmented with `bytes` and `seconds` for what was actually captured."""
    segments = unique_mood_segments(moods)
    # Cap concurrency so we don't trip Lyria rate limits. 3 in flight is a
    # safe sweet spot that cuts wall time ~3x for books with many moods.
    sem = asyncio.Semaphore(3)

    async def _bounded(seg: dict) -> None:
        async with sem:
            await _capture_segment(book_id, seg)
            path = segment_path(book_id, seg["idx"])
            size = path.stat().st_size if path.exists() else 0
            seg["bytes"] = size
            seg["seconds"] = size / BYTES_PER_SECOND

    await asyncio.gather(*(_bounded(s) for s in segments))
    return segments


def segments_ready(book_id: str, segments: list[dict]) -> bool:
    """True iff every segment file exists with at least a couple of seconds."""
    if not segments:
        return False
    min_bytes = BYTES_PER_SECOND * 2
    for seg in segments:
        path = segment_path(book_id, seg["idx"])
        if not path.exists() or path.stat().st_size < min_bytes:
            return False
    return True
