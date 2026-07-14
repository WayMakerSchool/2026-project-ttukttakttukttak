"""Tests for the photo->chapter matching hardening in camera_book.

Covers the pure hysteresis decision (decide_chapter) and the image quality
helpers (_sharpness_score / _enhance_for_vision). No network, no Gemini.

Run:  python test_chapter_detect.py   |   pytest test_chapter_detect.py
"""

import io

from PIL import Image, ImageDraw, ImageFilter

from camera_book import _enhance_for_vision, _sharpness_score, decide_chapter

N = 10  # pretend the book has 10 chapters


# --- decide_chapter: hysteresis ---------------------------------------------

def test_bad_read_holds_current():
    # -1 or out-of-range never drops the music / never moves.
    assert decide_chapter(3, -1, 0.9, N) == 3
    assert decide_chapter(3, 99, 0.9, N) == 3
    assert decide_chapter(3, -5, 0.9, N) == 3


def test_same_chapter_stays():
    assert decide_chapter(4, 4, 0.99, N) == 4


def test_high_confidence_switches_anywhere():
    assert decide_chapter(1, 7, 0.7, N) == 7  # far jump allowed when confident


def test_medium_confidence_only_adjacent():
    # Adjacent (page turn) accepted at medium confidence.
    assert decide_chapter(4, 5, 0.45, N) == 5
    assert decide_chapter(4, 3, 0.45, N) == 3
    # Far jump at medium confidence is rejected -> hold.
    assert decide_chapter(4, 8, 0.45, N) == 4


def test_low_confidence_holds():
    assert decide_chapter(4, 5, 0.2, N) == 4
    assert decide_chapter(4, 8, 0.2, N) == 4


def test_low_quality_raises_the_bar():
    # 0.7 would switch a far chapter normally, but a blurry image should not.
    assert decide_chapter(1, 7, 0.7, N, low_quality=True) == 1
    # Adjacent at 0.45 normally switches; blurry needs 0.55 -> holds.
    assert decide_chapter(4, 5, 0.45, N, low_quality=True) == 4
    # Strong enough still switches even when blurry.
    assert decide_chapter(1, 7, 0.85, N, low_quality=True) == 7


def test_initial_positioning_when_not_locked():
    # Before lock-on the reader may be anywhere: medium confidence can jump far.
    assert decide_chapter(0, 6, 0.45, N, locked=False) == 6
    # But a weak read still holds.
    assert decide_chapter(0, 6, 0.2, N, locked=False) == 0


def test_no_chapters():
    assert decide_chapter(2, 5, 0.9, 0) == 2


# --- image quality helpers --------------------------------------------------

def _text_image() -> Image.Image:
    img = Image.new("RGB", (800, 600), "white")
    d = ImageDraw.Draw(img)
    for y in range(40, 560, 26):
        d.text((40, y), "제3장 어느 흐린 날의 기록 — 본문 텍스트 라인입니다.", fill="black")
    return img


def test_sharpness_orders_sharp_above_blurred():
    sharp = _text_image()
    blurred = sharp.filter(ImageFilter.GaussianBlur(radius=4))
    assert _sharpness_score(sharp) > _sharpness_score(blurred)


def test_enhance_returns_jpeg_and_flags_ok():
    buf = io.BytesIO()
    _text_image().save(buf, format="PNG")
    out, score, ok = _enhance_for_vision(buf.getvalue())
    assert ok is True
    assert score > 0
    # Output must be a decodable JPEG.
    assert Image.open(io.BytesIO(out)).format == "JPEG"


def test_enhance_bad_bytes_degrades_gracefully():
    out, score, ok = _enhance_for_vision(b"not an image")
    assert ok is False
    assert score == 0.0
    assert out == b"not an image"  # original returned, nothing raised


if __name__ == "__main__":
    import sys

    funcs = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    failed = 0
    for fn in funcs:
        try:
            fn()
            print(f"  ok  {fn.__name__}")
        except AssertionError as e:
            failed += 1
            print(f" FAIL {fn.__name__}: {e!r}")
    print(f"\n{len(funcs) - failed}/{len(funcs)} passed")
    sys.exit(1 if failed else 0)
