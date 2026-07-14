"""Security primitives: rate limiting, JWT verify cache, ID validation.

These are intentionally simple in-memory implementations — appropriate for a
single-process FastAPI deployment. If you ever run multiple workers, swap
the dicts for Redis. The signatures stay the same.
"""

import re
import time
from typing import Any

from auth import verify_google_token

# --- Book ID validation ---------------------------------------------------
# IDs are sha256(file)[:16] → exactly 16 lowercase hex chars. Rejecting
# anything else early stops path-traversal attempts and shaves a DB query
# off the hot path for obviously malformed requests.
_BOOK_ID_RE = re.compile(r"^[a-f0-9]{16}$")


def is_valid_book_id(book_id: str) -> bool:
    return bool(_BOOK_ID_RE.match(book_id or ""))


# --- JWT verify cache -----------------------------------------------------
# Google ID token verification is mostly local (signature + claims) but it
# still costs a couple milliseconds per call, and every /books/{id}/*
# request runs the middleware. Caching by token-suffix for 60s gives us
# constant-time auth for hot reads without inflating the trust window —
# the cache TTL is well below the token's own ~1h expiry, and the verifier
# would catch a freshly-expired token within a minute either way.
_JWT_CACHE: dict[str, tuple[float, dict[str, Any] | None]] = {}
_JWT_TTL_SECONDS = 60.0


def verify_google_token_cached(token: str | None) -> dict[str, Any] | None:
    if not token:
        return None
    now = time.monotonic()
    # Use only the JWT's signature segment as the cache key — that's already
    # unique and short, avoids logging full tokens via debugger inspection.
    key = token.rsplit(".", 1)[-1][:32]
    entry = _JWT_CACHE.get(key)
    if entry and now - entry[0] < _JWT_TTL_SECONDS:
        return entry[1]
    claims = verify_google_token(token)
    _JWT_CACHE[key] = (now, claims)
    # Bounded cleanup — keep the cache small.
    if len(_JWT_CACHE) > 500:
        cutoff = now - _JWT_TTL_SECONDS
        for k in [k for k, (t, _) in _JWT_CACHE.items() if t < cutoff]:
            _JWT_CACHE.pop(k, None)
    return claims


# --- Rate limiter ---------------------------------------------------------
# Sliding-window counter. Keyed by an identity string the caller picks
# (typically `user:<email>` or `anon:<client-id>`). Returns the number of
# seconds the caller must wait, or 0 if allowed.
_BUCKETS: dict[str, list[float]] = {}


def rate_limit(key: str, *, limit: int, window_seconds: float) -> float:
    """Allow up to `limit` actions per `window_seconds` for this key.

    Returns 0.0 if allowed (call recorded), otherwise the number of
    seconds the caller must wait before retrying. Callers translate that
    into HTTP 429 with `Retry-After`."""
    now = time.monotonic()
    bucket = _BUCKETS.setdefault(key, [])
    cutoff = now - window_seconds
    # Drop timestamps that have aged out of the window.
    while bucket and bucket[0] < cutoff:
        bucket.pop(0)
    if len(bucket) >= limit:
        # Oldest timestamp + window = when the slot frees up.
        wait = bucket[0] + window_seconds - now
        return max(wait, 0.5)
    bucket.append(now)
    return 0.0


def rate_limit_identity(user: dict | None, client_id: str | None, fallback: str = "anon") -> str:
    """Build a stable rate-limit key for a requester."""
    if user and user.get("email"):
        return f"user:{user['email']}"
    if client_id:
        return f"cid:{client_id}"
    return fallback
