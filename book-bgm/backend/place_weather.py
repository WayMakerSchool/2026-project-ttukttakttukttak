"""Location + weather → a tiny music-tinting prompt.

The reader's browser sends GPS coordinates. From them we derive, with NO AI
calls and only free/keyless services:

- place type  (cafe / library / park / home / beach ...) via OpenStreetMap Nominatim
- weather     (rainy / clear / snowy ...) + day/night via Open-Meteo
- season      from the current month + hemisphere (sign of latitude)

These get mapped to a short English phrase that is fed to Lyria as a *secondary*
weighted prompt at weight 0.1 — so the reading environment tints the music about
10% while the book's own mood stays dominant (weight 0.9).

Everything degrades gracefully: any failed lookup is simply dropped, and if
nothing useful is found `location_music_context` returns None, which callers
treat exactly like "no location given" (music unchanged).
"""

import asyncio
import json
from datetime import datetime, timezone
from urllib.parse import urlencode
from urllib.request import Request, urlopen

# Nominatim's usage policy requires a descriptive User-Agent identifying the app.
_USER_AGENT = "GamseongDocs/1.0 (book background music; reading-environment tint)"
_HTTP_TIMEOUT = 3.0


# --- pure mappings (unit-tested; no network) --------------------------------

def weather_code_to_words(code: int | None) -> str:
    """WMO weather interpretation code → a short musical descriptor.

    Codes per Open-Meteo docs. Unknown/None → "" (caller drops it)."""
    if code is None:
        return ""
    if code == 0:
        return "clear bright sky"
    if code in (1, 2):
        return "soft drifting clouds"
    if code == 3:
        return "overcast grey"
    if code in (45, 48):
        return "misty fog"
    if code in (51, 53, 55, 56, 57):
        return "light drizzle"
    if code in (61, 63, 65, 66, 67):
        return "steady rain"
    if code in (71, 73, 75, 77):
        return "quiet snowfall"
    if code in (80, 81, 82):
        return "passing rain showers"
    if code in (85, 86):
        return "snow flurries"
    if code in (95, 96, 99):
        return "distant thunderstorm"
    return ""


def season_for(month: int, lat: float) -> str:
    """Meteorological season from month, flipped for the southern hemisphere."""
    northern = {
        12: "winter", 1: "winter", 2: "winter",
        3: "spring", 4: "spring", 5: "spring",
        6: "summer", 7: "summer", 8: "summer",
        9: "autumn", 10: "autumn", 11: "autumn",
    }
    season = northern.get(month, "")
    if lat < 0:  # southern hemisphere: seasons are six months out of phase
        flip = {"winter": "summer", "summer": "winter",
                "spring": "autumn", "autumn": "spring"}
        season = flip.get(season, season)
    return season


# OSM place type → descriptor. Keyed by the Nominatim `type` value; we also
# fall back on the broader `category` for things we don't enumerate.
_PLACE_TYPE_WORDS = {
    "cafe": "cozy cafe ambience, warm soft chatter",
    "restaurant": "warm bustling eatery",
    "fast_food": "warm bustling eatery",
    "bar": "low-lit lively bar",
    "pub": "low-lit lively bar",
    "library": "hushed library calm",
    "school": "studious campus air",
    "university": "studious campus air",
    "college": "studious campus air",
    "place_of_worship": "solemn sacred stillness",
    "cinema": "cinematic hush",
    "theatre": "cinematic hush",
    "arts_centre": "creative gallery mood",
    "hospital": "calm clinical quiet",
    "park": "open-air green park",
    "garden": "serene garden",
    "nature_reserve": "wild natural openness",
    "beach": "seaside breeze",
    "beach_resort": "seaside breeze",
    "water": "gentle waterside",
    "forest": "deep forest stillness",
    "wood": "deep forest stillness",
    "hotel": "quiet hotel room",
    "hostel": "quiet hotel room",
    "house": "quiet home indoors",
    "residential": "quiet home indoors",
    "apartments": "quiet home indoors",
}

_PLACE_CATEGORY_WORDS = {
    "shop": "lively shopping street",
    "leisure": "relaxed leisure setting",
    "tourism": "scenic getaway",
    "natural": "natural open air",
}


def place_category_to_words(category: str | None, type_: str | None) -> str | None:
    """Map an OSM (category, type) pair to a descriptor, or None if it's just
    a road/plot/anything not musically meaningful."""
    t = (type_ or "").strip().lower()
    if t in _PLACE_TYPE_WORDS:
        return _PLACE_TYPE_WORDS[t]
    c = (category or "").strip().lower()
    return _PLACE_CATEGORY_WORDS.get(c)


def compose_context(*parts: str | None) -> str | None:
    """Join the non-empty descriptor parts into one prompt line, or None."""
    cleaned = [p.strip() for p in parts if p and p.strip()]
    if not cleaned:
        return None
    return ", ".join(cleaned)


def _round_coord(value: float) -> float:
    # ~1.1 km granularity: enough for weather + place, coarse enough that we
    # never store/send a precise home location.
    return round(float(value), 2)


# --- network lookups (wrapped so they never raise to the caller) ------------

def _fetch_weather_sync(lat: float, lon: float) -> tuple[int | None, int | None, int | None]:
    """Returns (weather_code, is_day, local_month) or (None, None, None)."""
    qs = urlencode({
        "latitude": lat,
        "longitude": lon,
        "current": "weather_code,is_day",
        "timezone": "auto",
    })
    url = f"https://api.open-meteo.com/v1/forecast?{qs}"
    req = Request(url, headers={"User-Agent": _USER_AGENT})
    with urlopen(req, timeout=_HTTP_TIMEOUT) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    cur = data.get("current") or {}
    code = cur.get("weather_code")
    is_day = cur.get("is_day")
    month = None
    t = cur.get("time")  # local ISO like "2026-06-15T14:00"
    if isinstance(t, str) and len(t) >= 7:
        try:
            month = int(t[5:7])
        except ValueError:
            month = None
    return (
        int(code) if code is not None else None,
        int(is_day) if is_day is not None else None,
        month,
    )


def _fetch_place_sync(lat: float, lon: float) -> str | None:
    """Reverse-geocode to the nearest named feature and map it to a descriptor."""
    qs = urlencode({
        "lat": lat,
        "lon": lon,
        "format": "jsonv2",
        "zoom": 18,
        "addressdetails": 0,
    })
    url = f"https://nominatim.openstreetmap.org/reverse?{qs}"
    req = Request(url, headers={"User-Agent": _USER_AGENT})
    with urlopen(req, timeout=_HTTP_TIMEOUT) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return place_category_to_words(data.get("category"), data.get("type"))


async def location_music_context(lat: float | None, lon: float | None) -> str | None:
    """Build the ~10% environment prompt from coordinates, or None.

    Never raises: every lookup is best-effort. Season is always derivable from
    the date + hemisphere, so even with both network calls down we still return
    a (minimal) hemisphere-season tint."""
    if lat is None or lon is None:
        return None
    try:
        lat = _round_coord(lat)
        lon = _round_coord(lon)
    except (TypeError, ValueError):
        return None
    if not (-90.0 <= lat <= 90.0 and -180.0 <= lon <= 180.0):
        return None

    weather_words = ""
    day_words = ""
    month = datetime.now(timezone.utc).month
    try:
        code, is_day, local_month = await asyncio.to_thread(_fetch_weather_sync, lat, lon)
        weather_words = weather_code_to_words(code)
        if is_day is not None:
            day_words = "daytime" if is_day else "nighttime"
        if local_month:
            month = local_month
    except Exception as exc:  # noqa: BLE001 - best-effort, log and continue
        print(f"[place_weather] weather lookup failed: {exc!r}")

    place_words = None
    try:
        place_words = await asyncio.to_thread(_fetch_place_sync, lat, lon)
    except Exception as exc:  # noqa: BLE001
        print(f"[place_weather] place lookup failed: {exc!r}")

    season_words = season_for(month, lat)
    return compose_context(place_words, weather_words, day_words, season_words)
