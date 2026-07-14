"""Unit tests for the TOC-accuracy work: ISBN normalization, the scraped-TOC
local parser regression, cross-source reconciliation, and title
normalization (all pure functions, no network)."""

from camera_book import (
    _clean_isbn,
    _norm_title,
    _parse_scraped_toc_locally,
    reconcile_tocs,
)


def _chs(*titles):
    return [{"title": t, "summary": ""} for t in titles]


def test_clean_isbn_13_with_hyphens():
    assert _clean_isbn("979-11-6521-899-2") == "9791165218992"


def test_clean_isbn_13_with_prefix_and_spaces():
    assert _clean_isbn("ISBN 978-89-349-4246-7") == "9788934942467"


def test_clean_isbn_10_with_check_x():
    assert _clean_isbn("89-7297-011-X") == "897297011X"
    assert _clean_isbn("897297011x") == "897297011X"


def test_clean_isbn_rejects_wrong_length():
    assert _clean_isbn("12345") == ""
    assert _clean_isbn("97888888888888888") == ""


def test_clean_isbn_rejects_13_digit_non_bookland():
    # 13 digits that don't start with 978/979 is a random barcode, not ISBN.
    assert _clean_isbn("1234567890123") == ""


def test_clean_isbn_empty_and_none():
    assert _clean_isbn("") == ""
    assert _clean_isbn(None) == ""


def test_parse_scraped_toc_strips_page_numbers():
    toc = "제1장 새로운 시작 ........ 9\n제2장 폭풍 전야 15\n"
    out = _parse_scraped_toc_locally(toc)
    assert [c["title"] for c in out] == ["제1장 새로운 시작", "제2장 폭풍 전야"]
    assert [c["idx"] for c in out] == [0, 1]


def test_parse_scraped_toc_folds_subtopics_into_summary():
    toc = "1부 경제의 기초\n돈이란 무엇인가 / 은행의 탄생 / 금리의 원리\n2부 시장\n"
    out = _parse_scraped_toc_locally(toc)
    assert [c["title"] for c in out] == ["1부 경제의 기초", "2부 시장"]
    assert "돈이란 무엇인가" in out[0]["summary"]


# ── title normalization ──


def test_norm_title_ignores_spacing_and_punctuation():
    assert _norm_title("제3장. 도시의 밤") == _norm_title("제 3 장 도시의밤")
    assert _norm_title("Chapter 5: Dawn") == _norm_title("chapter5 dawn")


# ── cross-source reconciliation ──


def test_reconcile_prefers_first_source_with_chapters():
    r = reconcile_tocs([
        {"name": "aladin", "label": "알라딘", "chapters": []},
        {"name": "yes24", "label": "Yes24", "chapters": _chs("1장", "2장")},
    ])
    assert r["authority"] == "yes24"
    assert [c["title"] for c in r["toc"]] == ["1장", "2장"]


def test_reconcile_full_agreement_high_confidence():
    r = reconcile_tocs([
        {"name": "aladin", "label": "알라딘", "chapters": _chs("1장", "2장", "3장")},
        {"name": "yes24", "label": "Yes24", "chapters": _chs("1 장", "2장.", "3장")},
    ])
    assert r["authority"] == "aladin"
    assert "일치 ✅" in r["agreement"]
    assert r["confidence"] >= 0.95


def test_reconcile_disagreement_prefers_authority_and_notes_it():
    r = reconcile_tocs([
        {"name": "photo", "label": "촬영 목차", "chapters": _chs("A", "B", "C", "D")},
        {"name": "store", "label": "서점 목차", "chapters": _chs("X", "Y")},
    ])
    assert r["authority"] == "photo"
    assert len(r["toc"]) == 4
    assert "불일치" in r["agreement"]


def test_reconcile_borrows_summary_from_other_source():
    r = reconcile_tocs([
        {"name": "photo", "label": "촬영 목차",
         "chapters": [{"title": "1장", "summary": ""}]},
        {"name": "store", "label": "서점 목차",
         "chapters": [{"title": "1 장", "summary": "도시의 밤에 대하여"}]},
    ])
    assert r["toc"][0]["summary"] == "도시의 밤에 대하여"


def test_reconcile_all_empty_returns_none_authority():
    r = reconcile_tocs([
        {"name": "aladin", "label": "알라딘", "chapters": []},
        {"name": "yes24", "label": "Yes24", "chapters": []},
    ])
    assert r["authority"] == "none"
    assert r["toc"] == []
    assert r["confidence"] == 0.0


def test_reconcile_reindexes_merged_output():
    r = reconcile_tocs([
        {"name": "yes24", "label": "Yes24", "chapters": _chs("1장", "2장", "3장")},
    ])
    assert [c["idx"] for c in r["toc"]] == [0, 1, 2]


# ── Aladin official TTB API parsing (network layer mocked) ──

import asyncio
import json

import camera_book


_ALADIN_SAMPLE = json.dumps({
    "version": "20131101",
    "item": [{
        "title": "총 균 쇠",
        "link": "https://www.aladin.co.kr/shop/wproduct.aspx?ItemId=316294397",
        "subInfo": {
            "toc": (
                "프롤로그: 야리의 질문<br>1장 문명의 발전 격차<br>"
                "2장 역사의 자연 실험<br>에필로그"
            )
        },
    }],
})


def test_aladin_api_parses_toc(monkeypatch):
    monkeypatch.setattr(camera_book, "ALADIN_TTB_KEY", "TESTKEY")
    monkeypatch.setattr(camera_book, "_http_get", lambda url, timeout=6.0: _ALADIN_SAMPLE)
    out = asyncio.run(camera_book.crawl_toc_aladin_api("9788934942467"))
    assert "1장 문명의 발전 격차" in out["toc_text"]
    assert out["source_url"].endswith("ItemId=316294397")


def test_aladin_api_dormant_without_key(monkeypatch):
    monkeypatch.setattr(camera_book, "ALADIN_TTB_KEY", "")
    # Must not even attempt a fetch when no key is configured.
    monkeypatch.setattr(camera_book, "_http_get", lambda *a, **k: (_ for _ in ()).throw(AssertionError("network called")))
    assert asyncio.run(camera_book.crawl_toc_aladin_api("9788934942467")) == {}


def test_aladin_api_rejects_bad_isbn(monkeypatch):
    monkeypatch.setattr(camera_book, "ALADIN_TTB_KEY", "TESTKEY")
    monkeypatch.setattr(camera_book, "_http_get", lambda *a, **k: (_ for _ in ()).throw(AssertionError("network called")))
    assert asyncio.run(camera_book.crawl_toc_aladin_api("notanisbn")) == {}
