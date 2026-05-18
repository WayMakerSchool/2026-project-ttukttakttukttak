"""Google Sign-In token verification.

The frontend uses Google Identity Services to acquire an ID token (a JWT
signed by Google). We verify it server-side on every request that touches
auth-protected routes — never trust the email claim without that step.
"""

import os
from typing import Any

from google.auth.transport import requests as google_requests
from google.oauth2 import id_token as google_id_token

GOOGLE_CLIENT_ID = os.environ.get(
    "GOOGLE_CLIENT_ID",
    "799290215581-m1uo6jiai4na2ohfba9vektu84gv05kp.apps.googleusercontent.com",
)

# Reusing one Request() across calls keeps the HTTP connection pool warm.
_request = google_requests.Request()


def verify_google_token(token: str) -> dict[str, Any] | None:
    """Return verified claims (issuer, email, name, ...) or None on failure."""
    if not token:
        return None
    try:
        info = google_id_token.verify_oauth2_token(
            token, _request, GOOGLE_CLIENT_ID
        )
    except Exception as exc:
        print(f"[auth] token verification failed: {exc!r}")
        return None
    # Google's lib already checks signature, aud, exp. Sanity-check issuer.
    issuer = info.get("iss")
    if issuer not in {"accounts.google.com", "https://accounts.google.com"}:
        return None
    return info


def extract_bearer(authorization: str | None) -> str | None:
    if not authorization:
        return None
    parts = authorization.split(None, 1)
    if len(parts) != 2 or parts[0].lower() != "bearer":
        return None
    return parts[1].strip() or None
