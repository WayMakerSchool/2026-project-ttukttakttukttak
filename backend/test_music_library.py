"""Unit tests for the pre-generated music library matcher (pure logic,
no Lyria, no network — embeddings are faked, disk presence is patched)."""

import music_library as m


def _fake_ok(monkeypatch, ids=None):
    """Pretend every (or selected) track file exists on disk."""
    monkeypatch.setattr(
        m, "_track_ok", lambda tid: (ids is None or tid in ids)
    )


def _vec(*head):
    """768-dim unit-ish vector with a distinctive head."""
    v = [0.0] * m.EMBED_DIM
    for i, x in enumerate(head):
        v[i] = x
    return v


def test_catalog_has_100_tracks_10_moods():
    cat = m.load_library()
    assert len(cat) == 100
    assert len({t["mood_ko"] for t in cat}) == 10
    assert len({t["id"] for t in cat}) == 100  # unique ids


def test_cos_basic():
    assert m._cos([1, 0], [1, 0]) == 1.0
    assert m._cos([1, 0], [0, 1]) == 0.0
    assert m._cos([0, 0], [1, 0]) == 0.0  # zero vector safe


def test_fallback_matches_mood_then_bpm(monkeypatch):
    _fake_ok(monkeypatch)
    cat = m.load_library()
    ch = {"mood": "슬픔", "bpm": 55}
    t = m._fallback_match(ch, cat)
    assert t["mood_ko"] == "슬픔"
    assert abs(t["bpm"] - 55) <= 10


def test_fallback_unknown_mood_uses_bpm(monkeypatch):
    _fake_ok(monkeypatch)
    cat = m.load_library()
    t = m._fallback_match({"mood": "요상함", "bpm": 150}, cat)
    assert abs(t["bpm"] - 150) <= 15  # nearest-bpm pool-wide


def test_pick_with_embeddings_chooses_similar(monkeypatch):
    _fake_ok(monkeypatch)
    tracks = [
        {"id": "a", "mood_ko": "슬픔", "bpm": 60, "embedding": _vec(1.0)},
        {"id": "b", "mood_ko": "기쁨", "bpm": 120, "embedding": _vec(0.0, 1.0)},
    ]
    chapters = [{"mood": "?", "bpm": 0}]
    picks = m.pick_tracks_for_chapters(chapters, [_vec(0.9, 0.1)], tracks)
    assert picks[0]["id"] == "a"


def test_pick_rotates_within_top3_for_diversity(monkeypatch):
    _fake_ok(monkeypatch)
    # Three near-identical tracks: 5 chapters should NOT all land on one track.
    tracks = [
        {"id": f"t{i}", "mood_ko": "고요", "bpm": 70,
         "embedding": _vec(1.0, 0.01 * i)}
        for i in range(3)
    ]
    chapters = [{"mood": "고요", "bpm": 70}] * 5
    vecs = [_vec(1.0)] * 5
    picks = m.pick_tracks_for_chapters(chapters, vecs, tracks)
    assert len({p["id"] for p in picks}) >= 2


def test_pick_without_embeddings_falls_back(monkeypatch):
    _fake_ok(monkeypatch)
    cat = m.load_library()
    chapters = [{"mood": "분노", "bpm": 140}, {"mood": "고요", "bpm": 60}]
    picks = m.pick_tracks_for_chapters(chapters, None, cat)
    assert picks[0]["mood_ko"] == "분노"
    assert picks[1]["mood_ko"] == "고요"


def test_pick_only_uses_tracks_on_disk(monkeypatch):
    cat = m.load_library()
    only = {"calm_00", "calm_01"}
    _fake_ok(monkeypatch, ids=only)
    picks = m.pick_tracks_for_chapters([{"mood": "분노", "bpm": 150}], None, cat)
    assert picks[0]["id"] in only  # never picks a missing file


def test_nearest_track_for_mood_picks_closest(monkeypatch):
    import asyncio

    tracks = [
        {"id": "anger_00", "mood_ko": "분노", "bpm": 140, "embedding": _vec(1.0)},
        {"id": "calm_00", "mood_ko": "고요", "bpm": 60, "embedding": _vec(0.0, 1.0)},
    ]
    monkeypatch.setattr(m, "load_library", lambda: tracks)
    monkeypatch.setattr(m, "save_library", lambda t, **kw: None)
    monkeypatch.setattr(m, "_stored_embed_model", lambda: m.EMBED_MODEL)
    _fake_ok(monkeypatch)

    async def fake_embed(texts):  # query vector leans toward the calm axis
        return [_vec(0.05, 1.0)]

    monkeypatch.setattr(m, "_embed_texts", fake_embed)
    t = asyncio.run(m.nearest_track_for_mood("평화로운 정원 고요"))
    assert t["id"] == "calm_00"


def test_nearest_track_for_mood_hysteresis_keeps_current(monkeypatch):
    import asyncio

    # Two near-identical tracks; a near-tie must NOT bounce off the current one.
    tracks = [
        {"id": "calm_00", "mood_ko": "고요", "bpm": 60, "embedding": _vec(1.0, 0.0)},
        {"id": "calm_01", "mood_ko": "고요", "bpm": 62, "embedding": _vec(0.999, 0.01)},
    ]
    monkeypatch.setattr(m, "load_library", lambda: tracks)
    monkeypatch.setattr(m, "save_library", lambda t, **kw: None)
    monkeypatch.setattr(m, "_stored_embed_model", lambda: m.EMBED_MODEL)
    _fake_ok(monkeypatch)

    async def fake_embed(texts):
        return [_vec(1.0, 0.0)]

    monkeypatch.setattr(m, "_embed_texts", fake_embed)
    # calm_01 is currently playing; calm_00 is only marginally better → hold.
    t = asyncio.run(m.nearest_track_for_mood("고요", exclude_id="calm_01"))
    assert t["id"] == "calm_01"


def test_ensure_embeddings_reembeds_when_model_tag_missing(monkeypatch):
    """Regression: a library.json saved before model-tracking existed has
    embeddings but NO 'embed_model' tag. That must NOT be treated as
    'already on the current model' — those vectors are from whatever model
    was in use when they were cached, so silently trusting them would compare
    cosine similarity across two different vector spaces (meaningless)."""
    import asyncio

    tracks = [
        {"id": "sad_00", "mood_ko": "슬픔", "mood_en": "x", "prompt": "x",
         "bpm": 60, "embedding": _vec(1.0)},  # stale vector, no tag on disk
    ]
    monkeypatch.setattr(m, "load_library", lambda: tracks)
    monkeypatch.setattr(m, "_stored_embed_model", lambda: None)
    saved = {}
    monkeypatch.setattr(
        m, "save_library", lambda t, **kw: saved.update(tracks=t, **kw)
    )

    async def fake_embed(texts):
        return [_vec(9.9) for _ in texts]

    monkeypatch.setattr(m, "_embed_texts", fake_embed)
    out = asyncio.run(m.ensure_embeddings())
    assert out[0]["embedding"] == _vec(9.9)  # re-embedded, not the stale vector
    assert saved.get("embed_model") == m.EMBED_MODEL


def test_ensure_embeddings_skips_reembed_when_tag_matches(monkeypatch):
    """No wasted API calls when the cache is already on the current model."""
    import asyncio

    tracks = [{"id": "sad_00", "mood_ko": "슬픔", "bpm": 60, "embedding": _vec(1.0)}]
    monkeypatch.setattr(m, "load_library", lambda: tracks)
    monkeypatch.setattr(m, "_stored_embed_model", lambda: m.EMBED_MODEL)
    calls = []
    monkeypatch.setattr(m, "_embed_texts", lambda texts: calls.append(texts) or [])
    out = asyncio.run(m.ensure_embeddings())
    assert calls == []  # never called — nothing missing, nothing stale
    assert out[0]["embedding"] == _vec(1.0)  # untouched


def test_pick_raises_when_library_empty(monkeypatch):
    _fake_ok(monkeypatch, ids=set())
    try:
        m.pick_tracks_for_chapters([{"mood": "x"}], None, m.load_library())
        assert False, "should raise"
    except RuntimeError:
        pass
