"""Pre-generated music library + embedding matcher.

방향 전환 (2026-07-14): 세션마다 Lyria로 챕터 음악을 '그때그때' 생성하던
구조를 버리고, 감정별 트랙 ~100곡을 **한 번만** 미리 생성해둔다. 이후 모든
카메라 세션은 Lyria를 전혀 부르지 않는다 — 챕터의 무드·프롬프트를 임베딩해
라이브러리에서 가장 비슷한 트랙을 찾아 심링크로 배정할 뿐이다 (비용 0).

- 빌드(1회):  python music_library.py build          # 재개 가능, 파일 있으면 스킵
- 인덱스:     python music_library.py embed           # 트랙 텍스트 임베딩 캐시
- 상태:       python music_library.py status

라이브러리 포맷: storage/music_library/{track_id}.pcm (48kHz s16le stereo,
audio_cache와 동일) + library.json (트랙 메타 + 임베딩 벡터).
"""

import asyncio
import json
import math
import os
import sys
from pathlib import Path

from audio_cache import BYTES_PER_SECOND, capture_lyria_pcm

LIBRARY_ROOT = Path(__file__).parent / "storage" / "music_library"
LIBRARY_ROOT.mkdir(parents=True, exist_ok=True)
LIBRARY_JSON = LIBRARY_ROOT / "library.json"

# 라이브러리 트랙 길이. 카메라 세그먼트(20s)와 같은 포맷으로 루프 재생됨.
TRACK_DURATION_S = 20

# 임베딩 설정. gemini-embedding-001 @ 768차원 — 100트랙 JSON ≈ 1.5MB.
EMBED_MODEL = "gemini-embedding-001"
EMBED_DIM = 768

# ── 무드 택소노미: 10 카테고리 × 10 변주 = 100 트랙 ──────────────────────
# mood_ko는 TOC 파이프라인이 뱉는 무드 단어와 정합(슬픔/긴장/고요/환희/신비…).
# 변주는 악기·장르·질감을 바꿔 같은 감정 안에서도 폭을 가짐.
_VARIANTS: dict[str, tuple[str, list[str], tuple[int, int]]] = {
    # mood_en: (mood_ko, [10 english prompt variants], (bpm_min, bpm_max))
    "sad": ("슬픔", [
        "slow solo piano, mournful and sparse, heavy silence between notes",
        "melancholic cello and rain ambience, aching legato lines",
        "sad acoustic guitar arpeggios, distant reverb, grey afternoon",
        "grieving string quartet, low and tender, funeral pace",
        "lonely music box over soft vinyl crackle, faded memory",
        "somber ambient pads with a faint female hum, tearful",
        "slow jazz ballad, muted trumpet weeping over brushed drums",
        "minimal felt piano and cold synth wash, heartbreak at midnight",
        "elegiac choir pads, hollow and vast, mourning cathedral",
        "downtempo strings and sub bass, resigned and heavy-hearted",
    ], (50, 70)),
    "anger": ("분노", [
        "aggressive percussion and distorted bass stabs, seething fury",
        "driving industrial drums, metallic clangs, rising rage",
        "fierce taiko ensemble, thunderous and relentless",
        "distorted electric guitar riffs, storming and defiant",
        "dark orchestral hits with pounding timpani, wrathful",
        "fast breakbeat with growling synth bass, boiling anger",
        "harsh strings ostinato, stabbing staccato, vengeful drive",
        "heavy trap beat with sinister brass, confrontation",
        "punk-energy drums and fuzz bass, explosive temper",
        "cinematic battle percussion, war drums and shouts",
    ], (110, 160)),
    "joy": ("기쁨", [
        "bright acoustic pop strumming, handclaps, sunny morning",
        "playful pizzicato strings and glockenspiel, skipping along",
        "upbeat funk bass and clean guitar, irresistible groove",
        "cheerful ukulele and whistling, carefree summer day",
        "sparkling synth-pop arps, neon happiness, dance lightly",
        "bouncy piano boogie, joyful and mischievous",
        "feel-good indie folk with tambourine and la-la choir",
        "swing jazz combo, brushes and walking bass, grinning",
        "tropical house marimba and steel drums, beach celebration",
        "children's music box waltz, innocent delight",
    ], (95, 135)),
    "calm": ("고요", [
        "soft ambient pads, slow breathing, still lake at dawn",
        "gentle felt piano, warm tape hiss, quiet reading room",
        "airy flute over drone, mountain morning mist",
        "slow acoustic guitar fingerpicking, fireplace crackle",
        "warm electric piano chords, late-night calm, low lights",
        "minimal strings sustain, weightless and serene",
        "soft koto and water stream, zen garden stillness",
        "mellow vibraphone and upright bass, hushed lounge",
        "deep drone with faint bells, meditation cave",
        "lullaby harp glissandos, drifting into sleep",
    ], (55, 80)),
    "tense": ("긴장", [
        "pulsing low strings ostinato, ticking clock, held breath",
        "dark synth pulse with irregular percussion, stalking danger",
        "staccato cello riff, rising chromatic dread",
        "muted taiko heartbeat, footsteps in the dark",
        "suspenseful orchestral swells, knife-edge silence breaks",
        "nervous hi-hat shuffle over drone bass, cold sweat",
        "creeping piano cluster notes, thriller corridor",
        "glitchy electronics and metallic scrapes, surveillance",
        "low brass swells and snare rolls, storm approaching",
        "tremolo violins over deep pulse, cliffhanger",
    ], (85, 120)),
    "mystery": ("신비", [
        "ethereal choir and shimmering bells, ancient secret",
        "harp arpeggios in whole tones, moonlit riddle",
        "soft celesta and theremin, curious and otherworldly",
        "gamelan bells over deep drone, hidden temple",
        "whispering pads and reversed piano, dream logic",
        "music box in a minor key, abandoned attic",
        "slow modal oud and desert wind, caravan of secrets",
        "glass harmonica tones, floating question marks",
        "sparse kalimba and night insects, jungle enigma",
        "icy synth pads with distant echoes, aurora mystery",
    ], (60, 90)),
    "triumph": ("환희", [
        "soaring brass fanfare with timpani, victory gates open",
        "epic orchestral theme, strings racing skyward, heroic",
        "uplifting cinematic build with choir, summit reached",
        "majestic horns over galloping percussion, parade of champions",
        "anthemic rock with big drums and power chords, fists up",
        "triumphant piano octaves and full orchestra, golden light",
        "festival drumline and brass band, confetti burst",
        "gospel choir climax with organ, overwhelming joy",
        "orchestral waltz in full bloom, grand ballroom triumph",
        "electronic anthem, sidechained synths, stadium lights",
    ], (110, 150)),
    "nostalgia": ("그리움", [
        "warm vinyl piano ballad, sepia photographs, longing",
        "soft accordion waltz, old street corner in the rain",
        "dusty jazz trio, slow dance remembered, bittersweet",
        "lo-fi tape guitar and hum, summer that ended",
        "gentle strings and oboe, letters never sent",
        "music box and distant train, childhood home",
        "mellow trumpet over brushed drums, faded postcard",
        "acoustic guitar and harmonica, country road home",
        "retro synthwave pads, memories in neon",
        "slow cello and piano duet, first snow of that year",
    ], (60, 85)),
    "dreamy": ("몽환", [
        "hazy shoegaze guitars, floating through clouds",
        "ambient synth washes and slow arps, lucid dream",
        "reverb-drenched vocals-as-texture, weightless drift",
        "soft granular textures and heartbeat, underwater dream",
        "sparkling arpeggios and slow pad swells, starfield",
        "warm tape loops wobbling, half-asleep sunlight",
        "ethereal harp and airy choir, cloud palace",
        "chillwave beat with blurred keys, dusk balcony",
        "slow-motion strings and glass tones, gravity off",
        "gentle sine bells over deep space drone, orbit",
    ], (65, 95)),
    "dark": ("어둠", [
        "ominous low drone and distant rumbles, dread rising",
        "horror strings scraping over heartbeat, unseen presence",
        "deep dark ambient, dripping cave, cold breath",
        "ritual drums and low chant, forbidden ceremony",
        "dissonant piano and creaking wood, haunted house",
        "abyssal sub bass and metallic groans, deep sea dark",
        "night forest ambience with sinister pads, eyes watching",
        "funereal organ in a vast crypt, gothic shadow",
        "slow doom guitar and tolling bell, last light gone",
        "whispered voices over static, nightmare edge",
    ], (50, 80)),
}


def _catalog() -> list[dict]:
    """정적 트랙 카탈로그(파일 존재 여부와 무관) — id/무드/프롬프트/bpm."""
    tracks: list[dict] = []
    for mood_en, (mood_ko, prompts, (lo, hi)) in _VARIANTS.items():
        n = len(prompts)
        for i, prompt in enumerate(prompts):
            bpm = lo + round((hi - lo) * (i / max(1, n - 1)))
            tracks.append({
                "id": f"{mood_en}_{i:02d}",
                "mood_en": mood_en,
                "mood_ko": mood_ko,
                "prompt": prompt,
                "bpm": bpm,
            })
    return tracks


def track_path(track_id: str) -> Path:
    return LIBRARY_ROOT / f"{track_id}.pcm"


def _track_ok(track_id: str) -> bool:
    p = track_path(track_id)
    return p.exists() and p.stat().st_size >= BYTES_PER_SECOND * (TRACK_DURATION_S - 2)


def load_library() -> list[dict]:
    """library.json이 있으면 그걸, 없으면 정적 카탈로그를 반환."""
    if LIBRARY_JSON.exists():
        try:
            data = json.loads(LIBRARY_JSON.read_text())
            if isinstance(data, dict) and isinstance(data.get("tracks"), list):
                return data["tracks"]
        except Exception:
            pass
    return _catalog()


def save_library(tracks: list[dict]) -> None:
    LIBRARY_JSON.write_text(
        json.dumps({"tracks": tracks}, ensure_ascii=False), encoding="utf-8"
    )


def library_ready(min_tracks: int = 30) -> bool:
    """세션 배정에 쓸 만큼 트랙이 실존하는가. 전부일 필요는 없음 — 무드
    커버리지가 얼추 되면 세션은 Lyria 없이 굴러간다."""
    return sum(1 for t in load_library() if _track_ok(t["id"])) >= min_tracks


# ── 임베딩 ────────────────────────────────────────────────────────────────

def _track_text(t: dict) -> str:
    return f"{t['mood_ko']} ({t['mood_en']}). {t['prompt']}. tempo {t['bpm']} bpm"


def _chapter_text(ch: dict) -> str:
    parts = [
        str(ch.get("mood", "")).strip(),
        str(ch.get("music_prompt", "")).strip(),
        str(ch.get("summary", "")).strip()[:120],
    ]
    bpm = ch.get("bpm")
    if bpm:
        parts.append(f"tempo {bpm} bpm")
    return ". ".join(p for p in parts if p)


async def _embed_texts(texts: list[str]) -> list[list[float]] | None:
    """배치 임베딩. 실패 시 None (호출측은 무드 문자열 폴백 사용)."""
    try:
        from google import genai
        from google.genai import types as gtypes

        api_key = os.environ.get("GEMINI_API_KEY")
        if not api_key or not texts:
            return None
        client = genai.Client(api_key=api_key)
        vecs: list[list[float]] = []
        # 배치 한도(100) 여유를 두고 50개씩 청킹 — 100트랙 인덱싱도 2콜이면 끝.
        for i in range(0, len(texts), 50):
            chunk = texts[i : i + 50]
            resp = await asyncio.to_thread(
                client.models.embed_content,
                model=EMBED_MODEL,
                contents=chunk,
                config=gtypes.EmbedContentConfig(output_dimensionality=EMBED_DIM),
            )
            got = [list(e.values) for e in (resp.embeddings or [])]
            if len(got) != len(chunk):
                return None
            vecs.extend(got)
        return vecs if len(vecs) == len(texts) else None
    except Exception as exc:
        print(f"[music_library] embed failed: {exc!r}")
        return None


async def ensure_embeddings() -> list[dict]:
    """모든 트랙에 임베딩 벡터를 붙여 library.json에 캐시(1회 비용)."""
    tracks = load_library()
    missing = [t for t in tracks if not t.get("embedding")]
    if missing:
        vecs = await _embed_texts([_track_text(t) for t in missing])
        if vecs:
            for t, v in zip(missing, vecs):
                t["embedding"] = v
            save_library(tracks)
            print(f"[music_library] embedded {len(missing)} tracks")
    return tracks


def _cos(a: list[float], b: list[float]) -> float:
    num = sum(x * y for x, y in zip(a, b))
    da = math.sqrt(sum(x * x for x in a))
    db = math.sqrt(sum(x * x for x in b))
    return num / (da * db) if da and db else 0.0


def _fallback_match(ch: dict, candidates: list[dict]) -> dict:
    """임베딩이 없어도 동작하는 결정론 폴백: 무드 한글 라벨 일치 → bpm 근접."""
    mood = str(ch.get("mood", "")).strip()
    bpm = int(ch.get("bpm") or 80)
    same = [t for t in candidates if t["mood_ko"] == mood or t["mood_ko"] in mood or mood in t["mood_ko"]]
    pool = same or candidates
    return min(pool, key=lambda t: abs(int(t["bpm"]) - bpm))


def pick_tracks_for_chapters(
    chapters: list[dict],
    chapter_vecs: list[list[float]] | None,
    tracks: list[dict],
) -> list[dict]:
    """챕터마다 최유사 트랙 선택. 같은 트랙만 연속 반복되지 않게 상위 3
    후보 중 사용 횟수가 적은 트랙을 고른다(다양성). 순수 함수 — 테스트 가능."""
    avail = [t for t in tracks if _track_ok(t["id"])]
    if not avail:
        raise RuntimeError("music library has no usable tracks")
    used: dict[str, int] = {}
    out: list[dict] = []
    for i, ch in enumerate(chapters):
        vec = chapter_vecs[i] if chapter_vecs else None
        embedded = [t for t in avail if t.get("embedding")]
        if vec and embedded:
            ranked = sorted(
                embedded, key=lambda t: _cos(vec, t["embedding"]), reverse=True
            )
            top = ranked[:3]
            choice = min(top, key=lambda t: (used.get(t["id"], 0), -_cos(vec, t["embedding"])))
        else:
            choice = _fallback_match(ch, avail)
        used[choice["id"]] = used.get(choice["id"], 0) + 1
        out.append(choice)
    return out


async def nearest_track_for_mood(
    mood_text: str, exclude_id: str | None = None
) -> dict | None:
    """Embed a free-form page-mood description and return the single nearest
    library track. Powers the live 'page → song' loop (no TOC / chapter).

    `exclude_id` adds light hysteresis: when the best match is what's already
    playing we keep it, but if a DIFFERENT page mood wins we can optionally
    avoid immediately bouncing back to the same track on a near-tie."""
    tracks = await ensure_embeddings()
    avail = [t for t in tracks if _track_ok(t["id"])]
    if not avail:
        return None
    embedded = [t for t in avail if t.get("embedding")]
    text = (mood_text or "").strip()
    if text and embedded:
        vecs = await _embed_texts([text])
        if vecs:
            v = vecs[0]
            best = max(embedded, key=lambda t: _cos(v, t["embedding"]))
            # Stickiness: if a DIFFERENT track wins but the currently-playing one
            # is almost as good (<0.02 cosine gap), stay put — kills music jitter
            # when two consecutive pages read as nearly the same mood.
            if exclude_id and best["id"] != exclude_id:
                cur = next((t for t in embedded if t["id"] == exclude_id), None)
                if cur is not None and (
                    _cos(v, best["embedding"]) - _cos(v, cur["embedding"]) < 0.02
                ):
                    return cur
            return best
    # No embedding available → mood-word + bpm fallback.
    return _fallback_match({"mood": text, "bpm": 90}, avail)


async def assign_tracks(chapters: list[dict]) -> list[dict]:
    """세션 생성 시 호출: 챕터 리스트 → 트랙 리스트(같은 길이).
    임베딩은 세션당 배치 1콜(저렴), 실패해도 폴백으로 항상 결과를 냄."""
    tracks = await ensure_embeddings()
    vecs = await _embed_texts([_chapter_text(c) for c in chapters])
    return pick_tracks_for_chapters(chapters, vecs, tracks)


# ── 빌드 (1회, 재개 가능) ─────────────────────────────────────────────────

async def build_library(concurrency: int = 2) -> None:
    tracks = load_library()
    todo = [t for t in tracks if not _track_ok(t["id"])]
    print(f"[music_library] {len(tracks) - len(todo)}/{len(tracks)} ready, "
          f"building {len(todo)} tracks...")
    sem = asyncio.Semaphore(concurrency)
    done = 0

    async def _one(t: dict) -> None:
        nonlocal done
        async with sem:
            try:
                data = await capture_lyria_pcm(t["prompt"], t["bpm"], TRACK_DURATION_S)
                track_path(t["id"]).write_bytes(data)
                done += 1
                print(f"[music_library] ✓ {t['id']} ({done}/{len(todo)})")
            except Exception as exc:
                print(f"[music_library] ✗ {t['id']}: {exc!r}")

    await asyncio.gather(*(_one(t) for t in todo))
    save_library(tracks)
    ok = sum(1 for t in tracks if _track_ok(t["id"]))
    print(f"[music_library] build done: {ok}/{len(tracks)} tracks on disk")
    await ensure_embeddings()


def status() -> None:
    tracks = load_library()
    ok = [t for t in tracks if _track_ok(t["id"])]
    embedded = sum(1 for t in tracks if t.get("embedding"))
    by_mood: dict[str, int] = {}
    for t in ok:
        by_mood[t["mood_ko"]] = by_mood.get(t["mood_ko"], 0) + 1
    print(f"tracks on disk : {len(ok)}/{len(tracks)}")
    print(f"embedded       : {embedded}/{len(tracks)}")
    print(f"per mood       : {by_mood}")
    print(f"library ready  : {library_ready()}")


if __name__ == "__main__":
    from dotenv import load_dotenv
    load_dotenv(Path(__file__).parent / ".env")
    cmd = sys.argv[1] if len(sys.argv) > 1 else "status"
    if cmd == "build":
        asyncio.run(build_library())
    elif cmd == "embed":
        asyncio.run(ensure_embeddings())
    else:
        status()
