"""Unit tests for chapter-recognition matching: the deterministic layer that
maps the model's page reading (heading text / chapter number / running header)
to a TOC index, plus the decide_chapter hysteresis. All pure, no network."""

from camera_book import (
    _extract_chapter_key,
    _match_by_chapter_key,
    _match_by_title_text,
    resolve_detection,
    decide_chapter,
)

TOC = [
    {"idx": 0, "title": "프롤로그"},
    {"idx": 1, "title": "제1부 겨울"},
    {"idx": 2, "title": "제1장 시계태엽 튤립"},
    {"idx": 3, "title": "제2장 안개 도서관"},
    {"idx": 4, "title": "제3장 얼어붙은 분수대"},
    {"idx": 5, "title": "제2부 여름"},
    {"idx": 6, "title": "제4장 거꾸로 자라는 나무"},
    {"idx": 7, "title": "에필로그"},
]


# ── chapter-key extraction ──


def test_extract_key_chapter_and_part():
    assert _extract_chapter_key("제3장 얼어붙은 분수대") == ("장", 3)
    assert _extract_chapter_key("제2부 여름") == ("부", 2)
    assert _extract_chapter_key("Chapter 5: Dawn") == ("장", 5)
    assert _extract_chapter_key("Part 2") == ("부", 2)
    assert _extract_chapter_key("서문") is None


def test_part_and_chapter_do_not_cross_match():
    assert _match_by_chapter_key(TOC, ("부", 1)) == 1  # 제1부
    assert _match_by_chapter_key(TOC, ("장", 1)) == 2  # 제1장


def test_title_text_exact_and_containment():
    assert _match_by_title_text(TOC, "제2장 안개 도서관") == 3
    assert _match_by_title_text(TOC, "안개 도서관") == 3  # containment
    assert _match_by_title_text(TOC, "x") is None       # too short


# ── the key win: heading OCR right, model index wrong ──


def test_verbatim_heading_overrides_wrong_model_index():
    raw = {
        "signal": "heading",
        "heading_text": "제3장 얼어붙은 분수대",
        "chapter_number": 3,
        "heading_kind": "장",
        "chapter_idx": 0,   # model miscounted
        "confidence": 0.55,
    }
    r = resolve_detection(raw, TOC)
    assert r["idx"] == 4
    assert r["matched_by"] == "heading_text"
    assert r["confidence"] >= 0.9


def test_chapter_number_matches_when_heading_text_absent():
    raw = {"signal": "heading", "heading_text": "", "chapter_number": 2,
           "heading_kind": "장", "chapter_idx": 99, "confidence": 0.5}
    r = resolve_detection(raw, TOC)
    assert r["idx"] == 3  # 제2장
    assert r["matched_by"] == "chapter_number"


def test_part_number_kind_disambiguates():
    raw = {"chapter_number": 1, "heading_kind": "부", "chapter_idx": -1,
           "confidence": 0.6}
    assert resolve_detection(raw, TOC)["idx"] == 1  # 제1부, not 제1장


def test_running_header_used_when_no_heading():
    raw = {"signal": "running_header", "heading_text": "", "chapter_number": -1,
           "running_header": "안개 도서관", "chapter_idx": 0, "confidence": 0.4}
    r = resolve_detection(raw, TOC)
    assert r["idx"] == 3
    assert r["matched_by"] == "running_header"
    assert r["confidence"] >= 0.8


def test_body_inference_keeps_model_index_and_confidence():
    raw = {"signal": "body", "heading_text": "", "chapter_number": -1,
           "running_header": "", "chapter_idx": 6, "confidence": 0.62}
    r = resolve_detection(raw, TOC)
    assert r == {"idx": 6, "confidence": 0.62, "matched_by": "body"}


def test_none_signal_returns_minus_one():
    raw = {"signal": "none", "heading_text": "", "chapter_number": -1,
           "running_header": "", "chapter_idx": -1, "confidence": 0.1}
    assert resolve_detection(raw, TOC)["idx"] == -1


# ── decide_chapter honors a deterministic heading jump but resists body jitter ──


def test_deterministic_heading_allows_distant_jump():
    # conf 0.9 (heading) from chapter 0 → chapter 6: allowed.
    assert decide_chapter(0, 6, 0.9, len(TOC), locked=True) == 6


def test_body_confidence_blocks_distant_jump():
    # conf 0.5 (body) from chapter 0 → chapter 6 (not adjacent): held.
    assert decide_chapter(0, 6, 0.5, len(TOC), locked=True) == 0


def test_body_confidence_allows_adjacent_page_turn():
    assert decide_chapter(2, 3, 0.5, len(TOC), locked=True) == 3
