"""Unit tests for the TOC-accuracy work: ISBN normalization and the
scraped-TOC local parser regression (both pure functions, no network)."""

from camera_book import _clean_isbn, _parse_scraped_toc_locally


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
