"""Tests for the pure mapping logic in place_weather.

Runnable two ways:
    python test_place_weather.py      # standalone, no deps
    pytest test_place_weather.py      # if pytest is installed

Only the deterministic, network-free functions are covered here. The HTTP
lookups in location_music_context are best-effort and exercised manually.
"""

from place_weather import (
    compose_context,
    place_category_to_words,
    season_for,
    weather_code_to_words,
)


def test_weather_code_known_buckets():
    assert weather_code_to_words(0) == "clear bright sky"
    assert weather_code_to_words(3) == "overcast grey"
    assert weather_code_to_words(63) == "steady rain"
    assert weather_code_to_words(75) == "quiet snowfall"
    assert weather_code_to_words(95) == "distant thunderstorm"


def test_weather_code_unknown_or_none_is_empty():
    assert weather_code_to_words(None) == ""
    assert weather_code_to_words(12345) == ""


def test_season_northern_hemisphere():
    seoul_lat = 37.5
    assert season_for(1, seoul_lat) == "winter"
    assert season_for(4, seoul_lat) == "spring"
    assert season_for(7, seoul_lat) == "summer"
    assert season_for(10, seoul_lat) == "autumn"


def test_season_hemisphere_flip_explicit():
    # Northern July = summer; Southern July = winter.
    assert season_for(7, 37.5) == "summer"
    assert season_for(7, -33.8) == "winter"
    # Northern January = winter; Southern January = summer.
    assert season_for(1, 37.5) == "winter"
    assert season_for(1, -33.8) == "summer"


def test_place_type_mapping():
    assert place_category_to_words("amenity", "cafe") == "cozy cafe ambience, warm soft chatter"
    assert place_category_to_words("amenity", "library") == "hushed library calm"
    assert place_category_to_words("leisure", "park") == "open-air green park"


def test_place_category_fallback():
    # Unknown type but known category → category-level descriptor.
    assert place_category_to_words("shop", "boutique") == "lively shopping street"


def test_place_unmeaningful_is_none():
    # A road / generic feature maps to nothing.
    assert place_category_to_words("highway", "residential") == "quiet home indoors"  # residential type wins
    assert place_category_to_words("highway", "primary") is None
    assert place_category_to_words(None, None) is None


def test_compose_drops_empties_and_orders():
    assert compose_context("cozy cafe ambience", "steady rain", "nighttime", "autumn") == (
        "cozy cafe ambience, steady rain, nighttime, autumn"
    )
    assert compose_context(None, "", "  ", "spring") == "spring"
    assert compose_context(None, "", None) is None


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
