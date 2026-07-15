import hashlib
import json
import os
import re
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from pathlib import Path

import certifi

# macOS python.org builds ship without a system cert bundle, which makes the
# Lyria/Gemini TLS handshakes fail with "unable to get local issuer certificate".
os.environ.setdefault("SSL_CERT_FILE", certifi.where())
os.environ.setdefault("REQUESTS_CA_BUNDLE", certifi.where())

from dotenv import load_dotenv
from fastapi import Depends, FastAPI, Form, Header, HTTPException, Request, Response, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

import asyncio
import shutil

from audio_cache import generate_all_segments
from place_weather import location_music_context
from auth import extract_bearer, verify_google_token
from security import (
    is_valid_book_id,
    rate_limit,
    rate_limit_identity,
    verify_google_token_cached,
)
from ask_book import ask_book
from camera_book import (
    CAMERA_SERVER_URL,
    camera_segment_path,
    decide_chapter,
    detect_chapter_from_image,
    detect_page_mood,
    generate_all_camera_segments,
    generate_book_characters,
    generate_book_toc,
    MAX_TOC_ENTRIES,
    crawl_toc_for_book,
    crawl_toc_aladin_api,
    _format_scraped_toc,
    _parse_scraped_toc_locally,
    reconcile_tocs,
    extract_toc_from_photo,
    identify_book_from_cover,
    trigger_and_fetch_photo,
)
from chapter_image import generate_chapter_image
from style_designer import design_style, style_to_prompt_block
from chapter_summarizer import summarize_all_chapters
from characters_extractor import extract_characters
from cover_generator import generate_cover
from dict_lookup import lookup_word
from tts_generator import generate_page_tts
from db import (
    add_favorite,
    add_highlight,
    add_reading_session,
    add_review,
    book_exists,
    delete_book as db_delete_book,
    delete_highlight as db_delete_highlight,
    get_all_progress,
    get_book,
    get_book_image_style,
    get_chapter_summaries,
    get_characters,
    get_favorites,
    get_moods,
    get_progress,
    get_reading_stats,
    get_toc,
    set_characters,
    init_db,
    list_books,
    list_highlights,
    list_reviews,
    remove_favorite,
    set_audio_status,
    set_book_image_style,
    set_book_visibility,
    set_chapter_summary,
    set_progress,
    set_toc,
    update_book_metadata,
    update_description,
    upsert_book,
)
from epub_parser import extract_epub
from mood_analyzer import analyze_pages
from pdf_parser import extract_pages, extract_toc, normalize_cover_image, render_thumbnail
from toc_analyzer import analyze_toc
from ws_handler import MusicSession

load_dotenv()

STORAGE = Path(__file__).parent / "storage"
STORAGE.mkdir(exist_ok=True)

# Vite-built frontend lives at frontend/dist. When the dev server isn't
# available (memory-constrained machine, prod-style serving), FastAPI itself
# can serve the static bundle so the user only needs port 8000.
FRONTEND_DIST = Path(__file__).parent.parent / "frontend" / "dist"


@asynccontextmanager
async def lifespan(_app: FastAPI):
    init_db()
    # Self-heal: any book whose audio cache never finished gets re-queued.
    for book in list_books():
        if book.get("audio_status") in (None, "pending", "generating", "failed"):
            moods = get_moods(book["id"])
            if moods:
                asyncio.create_task(_generate_audio_background(book["id"], moods))
                print(f"[startup] re-queued audio generation for {book['id']}")
    yield


app = FastAPI(title="Book Background Music API", lifespan=lifespan)
# Dev frontend now serves over https (mkcert cert, see frontend/vite.config.ts)
# so a phone on the LAN can grant camera/mic permission — browsers block
# getUserMedia on any non-localhost origin served over plain http. Covers
# both http and https + localhost/127.0.0.1/LAN IP so nothing breaks mid-switch.
app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "https://localhost:5173",
        "https://127.0.0.1:5173",
        "https://192.168.0.188:5173",
        "http://localhost:5173",
    ],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# Hard limit on a single uploaded file. Holds an entire PDF in memory for
# parsing, so we cap to keep RAM bounded and stop trivial DoS attempts.
MAX_UPLOAD_BYTES = 200 * 1024 * 1024  # 200 MB


@app.middleware("http")
async def security_headers(request: Request, call_next):
    """Defense-in-depth response headers.

    - CSP locks down where scripts/iframes/connections can come from.
      `frame-src` includes Spline so the 3D hero scenes still load;
      `connect-src` includes wss for the Lyria WebSocket; `img-src` allows
      `data:`/`blob:` for in-memory previews (chapter images, AI covers).
    - X-Frame-Options stops anyone embedding our reader in a clickjack
      iframe.
    - X-Content-Type-Options blocks MIME sniffing (a PDF served with a
      wrong type can't be reinterpreted as HTML/script).
    - Referrer-Policy keeps the full URL (which carries `?token=` and
      `?cid=` query params) out of cross-origin Referer headers.
    - Permissions-Policy is a belt-and-suspenders deny list for sensitive
      browser APIs we never use.
    """
    response = await call_next(request)
    response.headers.setdefault(
        "Content-Security-Policy",
        "default-src 'self'; "
        "script-src 'self' 'unsafe-inline' https://accounts.google.com https://apis.google.com; "
        "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com https://accounts.google.com; "
        "font-src 'self' https://fonts.gstatic.com data:; "
        "img-src 'self' data: blob: https://lh3.googleusercontent.com; "
        "connect-src 'self' ws: wss: https://accounts.google.com; "
        "frame-src https://my.spline.design https://accounts.google.com; "
        "object-src 'none'; "
        "base-uri 'self'; "
        "form-action 'self'",
    )
    response.headers.setdefault("X-Content-Type-Options", "nosniff")
    response.headers.setdefault("X-Frame-Options", "DENY")
    response.headers.setdefault("Referrer-Policy", "no-referrer")
    response.headers.setdefault(
        "Permissions-Policy",
        "camera=(self), microphone=(), geolocation=(self), payment=(), usb=()",
    )
    return response


# Match /books/{book_id} and /books/{book_id}/anything (but not bare /books).
# The id segment is what we hand to get_book; sub-resource path is whatever
# follows.
_BOOK_PATH_RE = re.compile(r"^/books/([^/]+)(?:/|$)")


@app.middleware("http")
async def enforce_book_access(request: Request, call_next):
    """Central guard for every /books/{id}/... request.

    Why this exists: dozens of endpoints serve book content (PDF, text, TOC,
    chapter images, TTS, summaries, characters, ask). Adding a per-endpoint
    Depends to each is invasive and easy to forget on the next new route.
    A single path-matching middleware fails closed by default — any future
    book endpoint inherits the check automatically.

    Behavior:
    - Bare `/books` (the listing) is filtered inside the handler, not here.
    - CORS preflights pass through.
    - 404 (not 403) on access denial so book IDs aren't enumerable by
      probing for "exists but forbidden" vs "doesn't exist".
    """
    if request.method == "OPTIONS":
        return await call_next(request)
    m = _BOOK_PATH_RE.match(request.url.path)
    if not m:
        return await call_next(request)
    book_id = m.group(1)
    # Reject malformed IDs before hitting the DB. Stops path-traversal probes
    # and saves a query for obvious junk.
    if not is_valid_book_id(book_id):
        return JSONResponse({"detail": "Book not found"}, status_code=404)
    book = get_book(book_id)
    if not book:
        return JSONResponse({"detail": "Book not found"}, status_code=404)
    # Identity can arrive via headers (AJAX) OR query params (static URLs
    # like <img src> / <a href> that can't set custom headers).
    qp = request.query_params
    token = extract_bearer(request.headers.get("authorization")) or qp.get("token")
    user = verify_google_token_cached(token) if token else None
    client_id = request.headers.get("x-client-id") or qp.get("cid")
    if not book_visible_to(book, user, client_id):
        return JSONResponse({"detail": "Book not found"}, status_code=404)
    return await call_next(request)


async def current_user(authorization: str | None = Header(default=None)) -> dict | None:
    """Optional auth — returns Google claims if a valid token is present,
    else None. Route handlers decide whether to require it."""
    token = extract_bearer(authorization)
    if not token:
        return None
    return verify_google_token(token)


def require_user(user: dict | None = Depends(current_user)) -> dict:
    if user is None:
        raise HTTPException(401, "로그인이 필요합니다")
    return user


def reader_identifier(
    user: dict | None = Depends(current_user),
    x_client_id: str | None = Header(default=None, alias="X-Client-Id"),
) -> str:
    """Stable per-reader key for bookmarks/highlights. Uses Google email
    when logged in, otherwise a UUID the frontend stores in localStorage."""
    if user and user.get("email"):
        return f"user:{user['email']}"
    return f"anon:{x_client_id or 'unknown'}"


def _is_owner(book: dict, user: dict | None, client_id: str | None) -> bool:
    """The uploader can always access. Matches by Google email when signed in,
    otherwise by the anonymous X-Client-Id captured at upload time."""
    email = (user or {}).get("email")
    if email and book.get("uploader_email") and email == book["uploader_email"]:
        return True
    cid = (client_id or "").strip()
    if cid and book.get("uploader_client_id") and cid == book["uploader_client_id"]:
        return True
    # Legacy books predate ownership tracking — leave them open for now so
    # existing libraries don't break. New uploads always have an owner.
    if not book.get("uploader_email") and not book.get("uploader_client_id"):
        return True
    return False


def book_visible_to(book: dict, user: dict | None, client_id: str | None) -> bool:
    """A book is visible if it's public OR the requester owns it."""
    if book.get("visibility") == "public":
        return True
    return _is_owner(book, user, client_id)


def require_book_access(
    book_id: str,
    user: dict | None = Depends(current_user),
    x_client_id: str | None = Header(default=None, alias="X-Client-Id"),
) -> dict:
    """Fetch a book and enforce access. Returns the book dict on success.
    404 for unknown book IDs; 403 when the requester isn't allowed to see it.
    Using 404 vs 403 deliberately: existence is itself sensitive — we don't
    want to confirm a book ID exists to non-owners."""
    book = get_book(book_id)
    if not book:
        raise HTTPException(404, "Book not found")
    if not book_visible_to(book, user, x_client_id):
        raise HTTPException(404, "Book not found")
    return book


def require_book_owner(
    book_id: str,
    user: dict | None = Depends(current_user),
    x_client_id: str | None = Header(default=None, alias="X-Client-Id"),
) -> dict:
    """Stricter than access: must be the uploader (used for mutations)."""
    book = get_book(book_id)
    if not book:
        raise HTTPException(404, "Book not found")
    if not _is_owner(book, user, x_client_id):
        raise HTTPException(403, "이 책을 수정할 권한이 없습니다")
    return book


def _enforce_rate_limit(
    bucket: str,
    *,
    limit: int,
    window_seconds: float,
    user: dict | None,
    client_id: str | None,
) -> None:
    """Raise 429 if the caller has exceeded `limit` actions per window."""
    key = f"{bucket}:{rate_limit_identity(user, client_id)}"
    wait = rate_limit(key, limit=limit, window_seconds=window_seconds)
    if wait > 0:
        raise HTTPException(
            status_code=429,
            detail=f"요청이 너무 많아요. {int(wait + 1)}초 뒤에 다시 시도해주세요.",
            headers={"Retry-After": str(int(wait + 1))},
        )


def rate_limit_ai(
    user: dict | None = Depends(current_user),
    x_client_id: str | None = Header(default=None, alias="X-Client-Id"),
) -> None:
    """Text-only AI calls (ask, lookup, dict, characters extraction).
    Cheap per call but easy to abuse — 20/min per caller."""
    _enforce_rate_limit("ai", limit=20, window_seconds=60.0,
                        user=user, client_id=x_client_id)


def rate_limit_image(
    user: dict | None = Depends(current_user),
    x_client_id: str | None = Header(default=None, alias="X-Client-Id"),
) -> None:
    """Image generation (chapter images, AI covers). Each call hits Nano
    Banana / Imagen — much costlier — so a stricter 5/min cap."""
    _enforce_rate_limit("img", limit=5, window_seconds=60.0,
                        user=user, client_id=x_client_id)


def rate_limit_upload(
    user: dict | None = Depends(current_user),
    x_client_id: str | None = Header(default=None, alias="X-Client-Id"),
) -> None:
    """Uploads kick off chapter summarization + mood analysis + audio
    cache, so they're heavy. 3/min per caller is plenty."""
    _enforce_rate_limit("upload", limit=3, window_seconds=60.0,
                        user=user, client_id=x_client_id)


@app.get("/health")
def health() -> dict:
    return {"ok": True, "books": len(list_books())}


@app.get("/me")
def me(user: dict | None = Depends(current_user)) -> dict:
    if not user:
        return {"signedIn": False}
    return {
        "signedIn": True,
        "email": user.get("email"),
        "name": user.get("name"),
        "picture": user.get("picture"),
    }


ACCEPTED_COVER_MIME = {
    "image/png",
    "image/jpeg",
    "image/jpg",
    "image/webp",
    "image/gif",
}


async def _read_cover(cover: UploadFile | None) -> bytes | None:
    """Pull and validate an uploaded cover image, returning normalized PNG bytes."""
    if cover is None:
        return None
    if not getattr(cover, "filename", None):
        return None  # field present but empty
    if cover.content_type and cover.content_type not in ACCEPTED_COVER_MIME:
        raise HTTPException(400, f"Unsupported cover image type: {cover.content_type}")
    raw = await cover.read()
    if not raw:
        return None
    try:
        return normalize_cover_image(raw)
    except Exception as exc:
        raise HTTPException(400, f"Could not read cover image: {exc}")


def _detect_format(file: UploadFile, data: bytes) -> str:
    """Return 'epub' for EPUB uploads, otherwise 'pdf'."""
    ct = (file.content_type or "").lower()
    fn = (file.filename or "").lower()
    if "epub" in ct or fn.endswith(".epub"):
        return "epub"
    # Magic bytes: EPUB is a ZIP starting with PK\x03\x04.
    if data[:2] == b"PK":
        return "epub"
    return "pdf"


@app.post("/upload")
async def upload_book(
    file: UploadFile,
    description: str | None = Form(default=None),
    title_override: str | None = Form(default=None),
    author: str | None = Form(default=None),
    category: str | None = Form(default=None),
    subtitle: str | None = Form(default=None),
    translator: str | None = Form(default=None),
    publisher: str | None = Form(default=None),
    published_year: str | None = Form(default=None),  # str → int parsed below
    language: str | None = Form(default=None),
    isbn: str | None = Form(default=None),
    series_name: str | None = Form(default=None),
    series_index: str | None = Form(default=None),
    tags: str | None = Form(default=None),  # comma-separated
    # Optional reader location (browser GPS). Used once, only to tint the music
    # ~10% toward the local place/weather/season. Never stored or logged raw.
    lat: str | None = Form(default=None),
    lon: str | None = Form(default=None),
    # User must explicitly attest that they have the right to upload this
    # specific file. We record the timestamp so any later takedown dispute
    # has evidence of when the warranty was given. Required for legal/OSP
    # safe-harbor purposes — see /legal page in the frontend.
    consent_accepted: str = Form(default="false"),
    cover: UploadFile | None = None,
    user: dict | None = Depends(current_user),
    x_client_id: str | None = Header(default=None, alias="X-Client-Id"),
    _rl: None = Depends(rate_limit_upload),
) -> dict:
    def _clean(s: str | None) -> str | None:
        return s.strip() if s and s.strip() else None

    def _parse_year(s: str | None) -> int | None:
        if not s or not s.strip():
            return None
        try:
            y = int(s.strip())
            return y if 1 <= y <= 9999 else None
        except ValueError:
            return None

    def _parse_tags(s: str | None) -> list[str]:
        if not s:
            return []
        seen: set[str] = set()
        out: list[str] = []
        for tok in s.split(","):
            t = tok.strip()
            low = t.lower()
            if t and low not in seen and len(t) <= 40:
                seen.add(low)
                out.append(t)
            if len(out) >= 20:
                break
        return out

    extras = {
        "subtitle": _clean(subtitle),
        "translator": _clean(translator),
        "publisher": _clean(publisher),
        "published_year": _parse_year(published_year),
        "language": _clean(language),
        "isbn": _clean(isbn),
        "series_name": _clean(series_name),
        "series_index": _clean(series_index),
        "tags": _parse_tags(tags),
    }
    if consent_accepted.strip().lower() not in ("true", "1", "yes", "on"):
        raise HTTPException(
            400,
            "업로드하려면 본인이 권리를 가진 책임을 약관에서 확인해야 합니다.",
        )

    data = await file.read()
    if not data:
        raise HTTPException(400, "Empty file")
    if len(data) > MAX_UPLOAD_BYTES:
        # 413 = Payload Too Large. Reject after the read because Starlette
        # streams uploads to disk and we can't enforce a hard cap upstream.
        raise HTTPException(
            413,
            f"파일이 너무 큽니다 (최대 {MAX_UPLOAD_BYTES // (1024 * 1024)}MB)",
        )

    book_format = _detect_format(file, data)
    book_id = hashlib.sha256(data).hexdigest()[:16]
    desc = (description or "").strip() or None
    cover_png = await _read_cover(cover)

    if book_exists(book_id):
        update_book_metadata(
            book_id,
            title=_clean(title_override),
            author=_clean(author),
            category=_clean(category),
            description=desc,
            **extras,
        )
        if cover_png is not None:
            (STORAGE / f"{book_id}.thumb.png").write_bytes(cover_png)
        book = get_book(book_id)
        assert book is not None
        return {"book_id": book_id, "page_count": book["page_count"], "cached": True}

    if book_format == "epub":
        try:
            epub_data = extract_epub(data)
        except Exception as exc:
            raise HTTPException(400, f"EPUB 파싱 실패: {exc}")
        pages = epub_data["pages"]
        if not pages:
            raise HTTPException(400, "EPUB has no readable text")
        embedded_toc = epub_data["toc"]
        ebook_cover_bytes = epub_data.get("cover_bytes")
        ebook_title = epub_data.get("title") or ""
    else:
        if file.content_type not in {
            "application/pdf",
            "application/octet-stream",
            "",
            None,
        }:
            raise HTTPException(
                400, f"Unsupported content-type: {file.content_type}"
            )
        pages = extract_pages(data)
        if not pages:
            raise HTTPException(400, "PDF has no pages")
        embedded_toc = extract_toc(data)
        ebook_cover_bytes = None
        ebook_title = ""

    if embedded_toc:
        moods = await analyze_pages(pages)
        toc = embedded_toc
    else:
        moods, toc = await asyncio.gather(analyze_pages(pages), analyze_toc(pages))

    # Persist source bytes (.pdf or .epub).
    src_path = STORAGE / f"{book_id}.{book_format}"
    src_path.write_bytes(data)

    # Cover priority: user-uploaded > EPUB-embedded > PDF first-page render.
    thumb_path = STORAGE / f"{book_id}.thumb.png"
    if cover_png is not None:
        thumb_path.write_bytes(cover_png)
    elif ebook_cover_bytes:
        try:
            thumb_path.write_bytes(normalize_cover_image(ebook_cover_bytes))
        except Exception as exc:
            print(f"[main] epub cover normalize failed: {exc!r}")
    elif book_format == "pdf":
        try:
            thumb_path.write_bytes(render_thumbnail(data))
        except Exception as exc:
            print(f"[main] thumbnail render failed: {exc!r}")

    fallback_name = file.filename or f"untitled-{book_id}.{book_format}"
    title = (title_override or ebook_title or fallback_name).strip()
    uploader_email = (user or {}).get("email")
    uploader_name = (user or {}).get("name")
    upsert_book(
        book_id,
        title,
        len(pages),
        len(data),
        moods,
        description=desc,
        uploader_email=uploader_email,
        uploader_name=uploader_name,
        book_format=book_format,
        author=_clean(author),
        category=_clean(category),
        # Newly uploaded books are private by default — only the uploader
        # (matched by Google email or anon client-id) can access them.
        visibility="private",
        uploader_client_id=(x_client_id or "").strip() or None,
        consent_accepted_at=datetime.now(timezone.utc).isoformat(),
        **extras,
    )
    set_toc(book_id, toc)

    # Kick off Lyria audio generation in the background so future plays stream
    # from the disk cache instead of reopening a Lyria session each time.
    geo_lat, geo_lon = _parse_coord(lat), _parse_coord(lon)
    asyncio.create_task(
        _generate_audio_background(book_id, moods, lat=geo_lat, lon=geo_lon)
    )
    # And chapter summaries (only meaningful if the book has a real TOC).
    if toc:
        asyncio.create_task(_generate_summaries_background(book_id, toc, pages))

    return {"book_id": book_id, "page_count": len(pages), "cached": False}


async def _generate_summaries_background(book_id: str, toc: list[dict], pages: list[str]) -> None:
    try:
        summaries = await summarize_all_chapters(toc, pages)
        for page, summary in summaries.items():
            set_chapter_summary(book_id, page, summary)
        print(f"[summarizer] book {book_id}: {len(summaries)} chapter summaries saved")
    except Exception as exc:
        print(f"[summarizer] book {book_id} failed: {exc!r}")


def _parse_coord(value) -> float | None:
    """Parse a latitude/longitude that may arrive as a string form field."""
    if value is None or value == "":
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


async def _generate_audio_background(
    book_id: str,
    moods: list[dict],
    lat: float | None = None,
    lon: float | None = None,
) -> None:
    set_audio_status(book_id, "generating")
    try:
        context_prompt = await location_music_context(lat, lon)
        if context_prompt:
            print(f"[audio_cache] book {book_id} environment tint: {context_prompt!r}")
        segments = await generate_all_segments(book_id, moods, context_prompt=context_prompt)
        set_audio_status(book_id, "ready", segments)
        print(f"[audio_cache] book {book_id}: {len(segments)} segments ready")
    except Exception as exc:
        print(f"[audio_cache] book {book_id} failed: {exc!r}")
        set_audio_status(book_id, "failed")


@app.get("/books")
def list_all_books(
    identifier: str = Depends(reader_identifier),
    user: dict | None = Depends(current_user),
    x_client_id: str | None = Header(default=None, alias="X-Client-Id"),
) -> dict:
    # Filter at the gateway: each requester only sees books they own (or
    # public ones). Stops the library from exposing other users' uploads.
    books = [b for b in list_books() if book_visible_to(b, user, x_client_id)]
    progress_map = get_all_progress(identifier)
    favs = get_favorites(identifier)
    for b in books:
        prog = progress_map.get(b["id"])
        if prog:
            b["last_page"] = prog["page"]
            b["last_read_at"] = prog["updated_at"]
        b["favorited"] = b["id"] in favs
        # Don't leak internal owner identifiers in the listing.
        b.pop("uploader_client_id", None)
    return {"books": books}


@app.post("/books/{book_id}/favorite")
def add_favorite_endpoint(
    book_id: str,
    identifier: str = Depends(reader_identifier),
) -> dict:
    if not book_exists(book_id):
        raise HTTPException(404, "Book not found")
    add_favorite(identifier, book_id)
    return {"favorited": True}


@app.delete("/books/{book_id}/favorite", status_code=204)
def remove_favorite_endpoint(
    book_id: str,
    identifier: str = Depends(reader_identifier),
) -> Response:
    remove_favorite(identifier, book_id)
    return Response(status_code=204)


class SessionIn(BaseModel):
    book_id: str
    seconds: int = Field(ge=1, le=14400)  # 0-4 hours per session


@app.post("/me/sessions", status_code=201)
def record_session(
    body: SessionIn,
    identifier: str = Depends(reader_identifier),
) -> dict:
    if not book_exists(body.book_id):
        raise HTTPException(404, "Book not found")
    add_reading_session(identifier, body.book_id, body.seconds)
    return {"ok": True}


@app.get("/me/stats")
def me_stats(identifier: str = Depends(reader_identifier)) -> dict:
    return get_reading_stats(identifier)


@app.get("/books/{book_id}/characters")
async def get_book_characters(book_id: str) -> dict:
    """Returns the cached character/relationship map. Generates it on first
    request — one Gemini call per book, then served from DB forever."""
    book = get_book(book_id)
    if not book:
        raise HTTPException(404, "Book not found")
    cached = get_characters(book_id)
    if cached is not None:
        return cached

    fmt = book.get("format") or "pdf"
    src = STORAGE / f"{book_id}.{fmt}"
    if not src.exists():
        raise HTTPException(404, f"{fmt.upper()} file not found")
    pages = (
        extract_epub(src.read_bytes())["pages"]
        if fmt == "epub"
        else extract_pages(src.read_bytes())
    )
    try:
        data = await extract_characters(pages)
    except Exception as exc:
        print(f"[characters] {book_id} failed: {exc!r}")
        raise HTTPException(503, f"Character extraction failed: {exc}")
    set_characters(book_id, data)
    return data


@app.get("/books/{book_id}/recap")
def get_recap(
    book_id: str,
    identifier: str = Depends(reader_identifier),
) -> dict:
    """Build a "previously on…" summary from cached chapter summaries up to
    (and including) the chapter that contains the reader's last page.
    No new Gemini call — purely a JOIN of existing data."""
    book = get_book(book_id)
    if not book:
        raise HTTPException(404, "Book not found")
    prog = get_progress(book_id, identifier)
    last_page = (prog or {}).get("page") or 1
    if last_page <= 1:
        return {"last_page": last_page, "current_chapter": None, "previously": []}

    toc = get_toc(book_id) or []
    summaries = get_chapter_summaries(book_id)

    # Find the chapter the user was in: highest TOC entry with page <= last_page.
    sorted_toc = sorted(toc, key=lambda e: int(e.get("page", 0)))
    current_chapter = None
    previously: list[dict] = []
    for entry in sorted_toc:
        page = int(entry.get("page", 0))
        if page > last_page:
            break
        item = {
            "page": page,
            "title": entry.get("title", "").strip(),
            "summary": summaries.get(page, ""),
        }
        previously.append(item)
        current_chapter = item

    return {
        "last_page": last_page,
        "last_read_at": (prog or {}).get("updated_at"),
        "current_chapter": current_chapter,
        "previously": previously,
    }


@app.get("/books/{book_id}")
def get_book_meta(book_id: str) -> dict:
    book = get_book(book_id)
    if book is None:
        raise HTTPException(404, "Book not found")
    return book


class BookPatch(BaseModel):
    title: str | None = Field(default=None, max_length=300)
    author: str | None = Field(default=None, max_length=200)
    category: str | None = Field(default=None, max_length=80)
    description: str | None = Field(default=None, max_length=4000)
    subtitle: str | None = Field(default=None, max_length=300)
    translator: str | None = Field(default=None, max_length=200)
    publisher: str | None = Field(default=None, max_length=200)
    published_year: int | None = Field(default=None, ge=1, le=9999)
    language: str | None = Field(default=None, max_length=40)
    isbn: str | None = Field(default=None, max_length=40)
    series_name: str | None = Field(default=None, max_length=200)
    series_index: str | None = Field(default=None, max_length=20)
    tags: list[str] | None = Field(default=None, max_length=20)
    visibility: str | None = Field(default=None, pattern="^(private|public)$")


class ReviewIn(BaseModel):
    author: str | None = Field(default=None, max_length=40)
    rating: int = Field(ge=1, le=5)
    text: str = Field(min_length=1, max_length=2000)


class CoverGenIn(BaseModel):
    title: str = Field(min_length=1, max_length=300)
    description: str = Field(default="", max_length=2000)
    category: str = Field(default="", max_length=80)


@app.patch("/books/{book_id}")
def patch_book(
    book_id: str,
    body: BookPatch,
    _owner: dict = Depends(require_book_owner),
) -> dict:
    payload: dict = {}
    for name in (
        "title", "author", "category", "description",
        "subtitle", "translator", "publisher", "language",
        "isbn", "series_name", "series_index",
    ):
        v = getattr(body, name)
        if v is not None:
            payload[name] = v.strip()
    if body.published_year is not None:
        payload["published_year"] = body.published_year
    if body.tags is not None:
        seen: set[str] = set()
        cleaned: list[str] = []
        for t in body.tags:
            t = (t or "").strip()
            low = t.lower()
            if t and low not in seen and len(t) <= 40:
                seen.add(low)
                cleaned.append(t)
        payload["tags"] = cleaned
    update_book_metadata(book_id, **payload)
    if body.visibility is not None:
        set_book_visibility(book_id, body.visibility)
    book = get_book(book_id)
    assert book is not None
    return book


@app.post("/covers/generate")
async def post_generate_cover(
    body: CoverGenIn,
    _rl: None = Depends(rate_limit_image),
) -> Response:
    """Generate a book cover image (PNG) from title + description via Nano Banana.
    Used by the upload wizard for AI cover generation before committing the book."""
    try:
        png = await generate_cover(body.title, body.description, body.category)
    except Exception as exc:
        print(f"[covers] generation failed: {exc!r}")
        raise HTTPException(503, f"Cover generation failed: {exc}")
    return Response(
        content=png,
        media_type="image/png",
        headers={"Cache-Control": "no-store"},
    )


@app.delete("/books/{book_id}", status_code=204)
def remove_book(
    book_id: str,
    user: dict | None = Depends(current_user),
    x_client_id: str | None = Header(default=None, alias="X-Client-Id"),
) -> Response:
    book = get_book(book_id)
    if not book:
        raise HTTPException(404, "Book not found")
    if not _is_owner(book, user, x_client_id):
        raise HTTPException(403, "본인이 올린 책만 삭제할 수 있어요")

    # Drop the SQLite rows first; even if a file delete races we won't show
    # a half-deleted book.
    db_delete_book(book_id)
    for suffix in (".pdf", ".epub", ".thumb.png"):
        p = STORAGE / f"{book_id}{suffix}"
        try:
            p.unlink(missing_ok=True)
        except Exception as exc:
            print(f"[delete] failed to remove {p}: {exc!r}")
    for sub in ("audio", "tts", "images"):
        d = STORAGE / sub / book_id
        if d.exists():
            try:
                shutil.rmtree(d, ignore_errors=True)
            except Exception as exc:
                print(f"[delete] failed to remove {d}: {exc!r}")
    return Response(status_code=204)


@app.get("/books/{book_id}/reviews")
def get_reviews(book_id: str) -> dict:
    if not book_exists(book_id):
        raise HTTPException(404, "Book not found")
    return {"reviews": list_reviews(book_id)}


@app.post("/books/{book_id}/reviews", status_code=201)
def post_review(book_id: str, body: ReviewIn) -> dict:
    if not book_exists(book_id):
        raise HTTPException(404, "Book not found")
    return add_review(book_id, body.author, body.rating, body.text)


class ProgressIn(BaseModel):
    page: int = Field(ge=1, le=10000)


@app.put("/books/{book_id}/progress")
def put_book_progress(
    book_id: str,
    body: ProgressIn,
    identifier: str = Depends(reader_identifier),
) -> dict:
    if not book_exists(book_id):
        raise HTTPException(404, "Book not found")
    set_progress(book_id, identifier, body.page)
    return {"ok": True, "page": body.page}


@app.get("/books/{book_id}/progress")
def get_book_progress(
    book_id: str,
    identifier: str = Depends(reader_identifier),
) -> dict:
    if not book_exists(book_id):
        raise HTTPException(404, "Book not found")
    prog = get_progress(book_id, identifier)
    if not prog:
        return {"page": None, "updated_at": None}
    return prog


class HighlightIn(BaseModel):
    page: int = Field(ge=1)
    text: str = Field(min_length=1, max_length=2000)
    note: str | None = Field(default=None, max_length=1000)
    color: str | None = Field(default=None, max_length=20)


@app.post("/books/{book_id}/highlights", status_code=201)
def post_highlight(
    book_id: str,
    body: HighlightIn,
    identifier: str = Depends(reader_identifier),
) -> dict:
    if not book_exists(book_id):
        raise HTTPException(404, "Book not found")
    return add_highlight(book_id, identifier, body.page, body.text, body.note, body.color)


@app.get("/books/{book_id}/highlights")
def get_highlights(
    book_id: str,
    identifier: str = Depends(reader_identifier),
) -> dict:
    if not book_exists(book_id):
        raise HTTPException(404, "Book not found")
    return {"highlights": list_highlights(book_id, identifier)}


@app.delete("/books/{book_id}/highlights/{highlight_id}", status_code=204)
def remove_highlight(
    book_id: str,
    highlight_id: int,
    identifier: str = Depends(reader_identifier),
) -> Response:
    ok = db_delete_highlight(highlight_id, identifier)
    if not ok:
        raise HTTPException(404, "Highlight not found or not yours")
    return Response(status_code=204)


@app.get("/books/{book_id}/summaries")
def get_summaries(book_id: str) -> dict:
    if not book_exists(book_id):
        raise HTTPException(404, "Book not found")
    return {"summaries": get_chapter_summaries(book_id)}


@app.get("/books/{book_id}/text")
def get_book_text(book_id: str) -> dict:
    """Return the full text of the book, page by page. Used by the
    novel / comfort reading modes that reflow the content."""
    book = get_book(book_id)
    if not book:
        raise HTTPException(404, "Book not found")
    fmt = book.get("format") or "pdf"
    src = STORAGE / f"{book_id}.{fmt}"
    if not src.exists():
        raise HTTPException(404, f"{fmt.upper()} file not found on disk")
    if fmt == "epub":
        epub_data = extract_epub(src.read_bytes())
        return {"pages": epub_data["pages"]}
    return {"pages": extract_pages(src.read_bytes())}


class ChatTurn(BaseModel):
    role: str = Field(pattern="^(user|assistant)$")
    content: str = Field(min_length=1, max_length=4000)


class AskIn(BaseModel):
    question: str = Field(min_length=1, max_length=2000)
    page: int = Field(ge=1, default=1)
    history: list[ChatTurn] = Field(default_factory=list, max_length=20)


class LookupIn(BaseModel):
    text: str = Field(min_length=1, max_length=400)
    context: str = Field(default="", max_length=1000)


LOOKUP_PROMPT = """You are a quick reference helper for a reader. The user
selected this excerpt while reading:

"{text}"

{ctx}

Reply in Korean, in 2-4 short lines (no markdown):
1. If the excerpt is a single word or short phrase → explain its meaning concisely.
2. If it's in a foreign language → give a natural Korean translation.
3. If it's a longer Korean sentence → paraphrase its meaning more plainly,
   or note any difficult/literary words within it.
4. Keep it factual; do not invent context that isn't there.

Reply only with the explanation. No intro like '이 구절은...'"""


@app.post("/books/{book_id}/lookup")
async def post_lookup(
    book_id: str,
    body: LookupIn,
    _rl: None = Depends(rate_limit_ai),
) -> dict:
    if not book_exists(book_id):
        raise HTTPException(404, "Book not found")
    from google import genai
    from google.genai import types

    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        raise HTTPException(500, "GEMINI_API_KEY not configured")

    ctx = f"Surrounding text for context:\n{body.context.strip()}" if body.context.strip() else ""
    prompt = LOOKUP_PROMPT.replace("{text}", body.text.strip()).replace("{ctx}", ctx)
    client = genai.Client(api_key=api_key)
    try:
        resp = await asyncio.to_thread(
            client.models.generate_content,
            model="gemini-3.1-flash-lite",
            contents=prompt,
            config=types.GenerateContentConfig(
                temperature=0.3,
                max_output_tokens=300,
            ),
        )
    except Exception as exc:
        raise HTTPException(503, f"Lookup failed: {exc}")
    return {"result": (resp.text or "").strip()}


class DictLookupIn(BaseModel):
    word: str = Field(min_length=1, max_length=200)
    context: str = Field(default="", max_length=400)


@app.post("/dict/lookup")
async def post_dict_lookup(
    body: DictLookupIn,
    _rl: None = Depends(rate_limit_ai),
) -> dict:
    try:
        return await lookup_word(word=body.word, context=body.context or None)
    except RuntimeError as exc:
        raise HTTPException(500, str(exc))
    except Exception as exc:
        raise HTTPException(503, f"Dictionary lookup failed: {exc}")


@app.post("/books/{book_id}/ask")
async def post_ask(
    book_id: str,
    body: AskIn,
    _rl: None = Depends(rate_limit_ai),
) -> dict:
    book = get_book(book_id)
    if not book:
        raise HTTPException(404, "Book not found")
    fmt = book.get("format") or "pdf"
    src = STORAGE / f"{book_id}.{fmt}"
    if not src.exists():
        raise HTTPException(404, f"{fmt.upper()} file not found")
    if fmt == "epub":
        pages = extract_epub(src.read_bytes())["pages"]
    else:
        pages = extract_pages(src.read_bytes())
    toc = get_toc(book_id) or []
    summaries = get_chapter_summaries(book_id)
    try:
        answer = await ask_book(
            title=book.get("title", ""),
            description=book.get("description"),
            toc=toc,
            summaries=summaries,
            pages=pages,
            current_page=body.page,
            history=[{"role": t.role, "content": t.content} for t in body.history],
            question=body.question,
        )
    except Exception as exc:
        print(f"[ask] failed: {exc!r}")
        raise HTTPException(503, f"AI 응답 실패: {exc}")
    return {"answer": answer or "(답변을 만들지 못했어요)"}


@app.get("/books/{book_id}/pages/{page}/tts")
async def get_page_tts(book_id: str, page: int) -> Response:
    """Stream Gemini TTS audio for a single page. Cached to disk so each
    page is generated at most once per book regardless of how many users
    re-listen to it later."""
    if not book_exists(book_id):
        raise HTTPException(404, "Book not found")
    if page < 1:
        raise HTTPException(400, "Page must be >= 1")

    cache_dir = STORAGE / "tts" / book_id
    cache_dir.mkdir(parents=True, exist_ok=True)
    cache_path = cache_dir / f"p{page}.wav"

    if cache_path.exists() and cache_path.stat().st_size > 44:
        return Response(
            content=cache_path.read_bytes(),
            media_type="audio/wav",
            headers={"Cache-Control": "private, max-age=86400"},
        )

    book = get_book(book_id)
    fmt = (book or {}).get("format") or "pdf"
    src = STORAGE / f"{book_id}.{fmt}"
    if not src.exists():
        raise HTTPException(404, f"{fmt.upper()} not found on disk")
    if fmt == "epub":
        pages = extract_epub(src.read_bytes())["pages"]
    else:
        pages = extract_pages(src.read_bytes())
    if page > len(pages):
        raise HTTPException(400, f"Page {page} out of range (book has {len(pages)})")

    text = (pages[page - 1] or "").strip()
    if not text:
        from tts_generator import pcm_to_wav
        return Response(content=pcm_to_wav(b""), media_type="audio/wav")

    try:
        wav = await generate_page_tts(text)
    except Exception as exc:
        print(f"[tts] generation failed for {book_id} p{page}: {exc!r}")
        raise HTTPException(503, f"TTS generation failed: {exc}")

    if not wav:
        raise HTTPException(503, "Gemini returned empty audio")

    cache_path.write_bytes(wav)
    return Response(
        content=wav,
        media_type="audio/wav",
        headers={"Cache-Control": "private, max-age=86400"},
    )


def _chapter_window(toc: list[dict], page: int, page_count: int) -> tuple[int, str, int, int]:
    """Find the chapter that contains `page` and return
    (start_page, title, window_start, window_end) — inclusive 1-based bounds."""
    sorted_toc = sorted(toc or [], key=lambda e: int(e.get("page", 0)))
    start = 1
    title = ""
    end = page_count
    for i, entry in enumerate(sorted_toc):
        p = int(entry.get("page", 0))
        if p > page:
            end = p - 1
            break
        start = p
        title = str(entry.get("title", "")).strip()
        if i + 1 < len(sorted_toc):
            end = int(sorted_toc[i + 1].get("page", page_count + 1)) - 1
        else:
            end = page_count
    return start, title, max(1, start), min(page_count, max(start, end))


@app.api_route("/books/{book_id}/chapters/{page}/image", methods=["GET", "HEAD"])
def get_chapter_image(book_id: str, page: int) -> Response:
    """Return the cached illustration for the chapter that CONTAINS `page`.
    One image per chapter — cache key is the chapter's start page, so a
    mid-chapter request still finds the right file."""
    book = get_book(book_id)
    if not book:
        raise HTTPException(404, "Book not found")
    page_count = int(book.get("page_count", 0) or 0) or page
    toc = get_toc(book_id) or []
    start, _, _, _ = _chapter_window(toc, page, page_count)
    path = STORAGE / "images" / book_id / f"p{start}.png"
    if not path.exists():
        raise HTTPException(404, "Image not generated yet")
    return Response(
        content=path.read_bytes(),
        media_type="image/png",
        headers={"Cache-Control": "private, max-age=86400"},
    )


@app.post("/books/{book_id}/chapters/{page}/image")
async def create_chapter_image(
    book_id: str,
    page: int,
    _rl: None = Depends(rate_limit_image),
) -> Response:
    """Generate (or regenerate) an illustration for the chapter containing
    `page` — one image per chapter. Result cached so subsequent GETs are free."""
    book = get_book(book_id)
    if not book:
        raise HTTPException(404, "Book not found")
    if page < 1:
        raise HTTPException(400, "Page must be >= 1")

    fmt = book.get("format") or "pdf"
    src = STORAGE / f"{book_id}.{fmt}"
    if not src.exists():
        raise HTTPException(404, f"{fmt.upper()} file not found")

    if fmt == "epub":
        pages = extract_epub(src.read_bytes())["pages"]
    else:
        pages = extract_pages(src.read_bytes())
    if not pages:
        raise HTTPException(400, "Book has no readable text")

    toc = get_toc(book_id) or []
    start, title, win_start, win_end = _chapter_window(toc, page, len(pages))
    summary = get_chapter_summaries(book_id).get(start, "")
    # Pull a generous slice of the chapter's actual text so the model
    # illustrates the chapter's events — not just the TOC title. Up to ~4k
    # chars covering opening, middle, and end of the chapter window.
    chapter_pages = pages[win_start - 1 : win_end]
    if chapter_pages:
        full = "\n\n".join(chapter_pages)
        if len(full) <= 4000:
            excerpt = full
        else:
            # Take opening + middle + ending so the prompt sees the arc.
            head = full[:1800]
            mid_start = max(0, len(full) // 2 - 600)
            mid = full[mid_start : mid_start + 1200]
            tail = full[-800:]
            excerpt = f"{head}\n\n[…중반…]\n{mid}\n\n[…후반…]\n{tail}"
    else:
        excerpt = ""

    # Per-book style sheet — generated lazily on the first chapter image
    # call, then cached for every later chapter so the gallery looks like
    # one coherent volume instead of 12 separate stock illustrations.
    style = get_book_image_style(book_id)
    if not style:
        try:
            chars_data = get_characters(book_id) or {}
            chars_list = chars_data.get("characters") if isinstance(chars_data, dict) else None
            sample = "\n\n".join(pages[:3])[:3000]
            style = await design_style(
                title=book.get("title", ""),
                description=book.get("description"),
                category=book.get("category"),
                language=book.get("language"),
                published_year=book.get("published_year"),
                characters=chars_list,
                sample_text=sample,
            )
            set_book_image_style(book_id, style)
            print(f"[style] designed for {book_id}")
        except Exception as exc:
            # Non-fatal — fall back to no style block, image will still
            # generate (just less consistent across chapters).
            print(f"[style] design failed for {book_id}: {exc!r}")
            style = None
    style_block = style_to_prompt_block(style) if style else ""

    try:
        png = await generate_chapter_image(title, summary, excerpt, style_block)
    except Exception as exc:
        print(f"[chapter_image] {book_id} p{page} failed: {exc!r}")
        raise HTTPException(503, f"Image generation failed: {exc}")

    out_dir = STORAGE / "images" / book_id
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / f"p{start}.png"
    out_path.write_bytes(png)

    return Response(
        content=png,
        media_type="image/png",
        headers={"Cache-Control": "private, max-age=86400"},
    )


@app.get("/books/{book_id}/images")
def list_chapter_images(book_id: str) -> dict:
    """Return the set of pages that already have a cached illustration."""
    if not book_exists(book_id):
        raise HTTPException(404, "Book not found")
    out_dir = STORAGE / "images" / book_id
    if not out_dir.exists():
        return {"pages": []}
    pages: list[int] = []
    for p in out_dir.glob("p*.png"):
        try:
            pages.append(int(p.stem[1:]))
        except ValueError:
            continue
    return {"pages": sorted(pages)}


@app.get("/books/{book_id}/moods")
def get_book_moods(book_id: str) -> dict:
    moods = get_moods(book_id)
    if moods is None:
        raise HTTPException(404, "Book not found")
    return {"moods": moods}


@app.get("/books/{book_id}/toc")
async def get_book_toc(book_id: str) -> dict:
    if not book_exists(book_id):
        raise HTTPException(404, "Book not found")
    toc = get_toc(book_id)
    if toc is not None:
        return {"toc": toc}
    # Lazy fallback for older books that were uploaded before TOC support.
    pdf_path = STORAGE / f"{book_id}.pdf"
    pages = get_moods(book_id)  # length is page_count
    extracted: list[dict] = []
    if pdf_path.exists():
        extracted = extract_toc(pdf_path.read_bytes())
    if not extracted and pages is not None:
        # We don't have the page text in DB; re-extract from disk PDF.
        if pdf_path.exists():
            text_pages = extract_pages(pdf_path.read_bytes())
            extracted = await analyze_toc(text_pages)
    set_toc(book_id, extracted)
    return {"toc": extracted}


@app.get("/books/{book_id}/pdf")
def get_pdf(book_id: str) -> Response:
    book = get_book(book_id)
    if not book:
        raise HTTPException(404, "Book not found")
    if (book.get("format") or "pdf") != "pdf":
        raise HTTPException(400, "This book is an EPUB. Use the text reader modes.")
    pdf_path = STORAGE / f"{book_id}.pdf"
    if not pdf_path.exists():
        raise HTTPException(404, "PDF not found")
    return Response(
        content=pdf_path.read_bytes(),
        media_type="application/pdf",
        headers={"Cache-Control": "private, max-age=3600"},
    )


@app.post("/books/{book_id}/cover", status_code=200)
async def replace_cover(
    book_id: str,
    cover: UploadFile,
    _owner: dict = Depends(require_book_owner),
) -> dict:
    cover_png = await _read_cover(cover)
    if cover_png is None:
        raise HTTPException(400, "No cover image provided")
    (STORAGE / f"{book_id}.thumb.png").write_bytes(cover_png)
    return {"ok": True, "bytes": len(cover_png)}


@app.get("/books/{book_id}/thumb")
def get_thumb(book_id: str) -> Response:
    thumb_path = STORAGE / f"{book_id}.thumb.png"
    if not thumb_path.exists():
        raise HTTPException(404, "Thumbnail not found")
    # no-cache (revalidate, not "don't cache"): covers can be replaced via
    # POST /books/{id}/cover or a re-upload — a day-long max-age kept showing
    # the stale page-1 render after the user set a real cover.
    return Response(
        content=thumb_path.read_bytes(),
        media_type="image/png",
        headers={"Cache-Control": "no-cache"},
    )


# =========================================================================
# Camera-driven reader — user types a book name, AI generates a TOC + per-
# chapter Lyria segment, an external camera identifies the current chapter
# from a snapshot of the page, and the matching segment becomes the live
# stream. State is in-memory because sessions are inherently ephemeral.
# =========================================================================

CAMERA_SESSIONS: dict[str, dict] = {}
_CAMERA_PROMPT_TIMES: dict[str, float] = {}


class CameraChapterIn(BaseModel):
    idx: int = 0
    title: str = ""
    summary: str = ""
    music_prompt: str = ""
    bpm: int = 80
    mood: str = ""


class CameraSessionIn(BaseModel):
    book_name: str = Field(min_length=1, max_length=200)
    author: str = Field(default="", max_length=120)
    publisher: str = Field(default="", max_length=120)
    translator: str = Field(default="", max_length=120)
    edition: str = Field(default="", max_length=80)
    # If the client already has a TOC (from /camera/identify or /camera/toc-lookup),
    # pass it here so we don't regenerate. This is what the user explicitly asked
    # for: lock in the TOC they already saw, no second guessing.
    toc: list[CameraChapterIn] | None = None
    matched_edition: str = ""
    # Optional reader location (browser GPS) → ~10% music tint. Not stored raw.
    lat: float | None = None
    lon: float | None = None


def _camera_session_public(session: dict) -> dict:
    return {
        "id": session["id"],
        "book_name": session["book_name"],
        "author": session.get("author", ""),
        "publisher": session.get("publisher", ""),
        "translator": session.get("translator", ""),
        "edition": session.get("edition", ""),
        "matched_edition": session.get("matched_edition", ""),
        "toc": [
            {
                "idx": c["idx"],
                "title": c["title"],
                "summary": c.get("summary", ""),
                "mood": c.get("mood", ""),
                "bpm": c.get("bpm", 80),
            }
            for c in session["toc"]
        ],
        "current_chapter_idx": session["current_chapter_idx"],
        "audio_status": session["audio_status"],
        "ready_segments": sorted(session.get("ready_segments", [])),
        "last_detection": session.get("last_detection"),
        "camera_server": CAMERA_SERVER_URL,
    }


def _require_camera_session(
    session_id: str,
    user: dict | None,
    client_id: str | None,
) -> dict:
    s = CAMERA_SESSIONS.get(session_id)
    if not s:
        raise HTTPException(404, "세션을 찾을 수 없어요")
    owner_email = s.get("owner_email")
    owner_cid = s.get("owner_client_id")
    email = (user or {}).get("email")
    if owner_email and email and email == owner_email:
        return s
    if owner_cid and client_id and client_id == owner_cid:
        return s
    if not owner_email and not owner_cid:
        return s
    raise HTTPException(404, "세션을 찾을 수 없어요")


async def _generate_camera_audio_background(
    session_id: str,
    toc: list[dict],
    lat: float | None = None,
    lon: float | None = None,
) -> None:
    session = CAMERA_SESSIONS.get(session_id)
    if not session:
        return
    session["audio_status"] = "generating"
    session["ready_segments"] = []

    # 사전 생성 라이브러리가 있으면 Lyria를 아예 부르지 않는다: 챕터 무드를
    # 임베딩(세션당 배치 1콜)으로 최유사 트랙에 매칭하고 심링크만 건다.
    # 수 초 안에 전 챕터 ready + 생성 비용 0. 라이브러리가 없거나 배정이
    # 실패하면 기존 Lyria 경로로 폴백.
    import music_library

    if music_library.library_ready():
        try:
            picks = await music_library.assign_tracks(toc)
            for ch, tr in zip(toc, picks):
                dst = camera_segment_path(session_id, ch["idx"])
                src = music_library.track_path(tr["id"])
                if dst.exists() or dst.is_symlink():
                    dst.unlink()
                dst.symlink_to(src)
                ch["track_id"] = tr["id"]
                session["ready_segments"].append(ch["idx"])
            # Start playing the first chapter's match immediately; the live
            # page→mood loop takes over current_track_id on the first /detect.
            if picks:
                session["current_track_id"] = picks[0]["id"]
            session["audio_status"] = "ready"
            print(
                f"[camera] {session_id} library-matched {len(picks)} chapters "
                f"(no Lyria): "
                + ", ".join(f"{c['idx']}→{t['id']}" for c, t in zip(toc[:8], picks[:8]))
                + ("…" if len(picks) > 8 else "")
            )
            return
        except Exception as exc:
            print(f"[camera] {session_id} library assign failed, "
                  f"falling back to Lyria: {exc!r}")
            session["ready_segments"] = []

    context_prompt = await location_music_context(lat, lon)
    if context_prompt:
        print(f"[camera] {session_id} environment tint: {context_prompt!r}")

    sem = asyncio.Semaphore(3)

    async def _one(chapter: dict) -> None:
        async with sem:
            from camera_book import _capture_chapter_segment

            try:
                await _capture_chapter_segment(
                    session_id, chapter, context_prompt=context_prompt
                )
                session["ready_segments"].append(chapter["idx"])
                print(
                    f"[camera] {session_id} chapter {chapter['idx']} ready "
                    f"({len(session['ready_segments'])}/{len(toc)})"
                )
            except Exception as exc:
                print(
                    f"[camera] {session_id} chapter {chapter['idx']} failed: {exc!r}"
                )

    try:
        await asyncio.gather(*(_one(c) for c in toc))
        ok = len(session["ready_segments"])
        # Partial success still plays: chapters whose segment is missing
        # fall back to the nearest ready segment at stream time, so any
        # non-zero count is a usable session — not a failure.
        session["audio_status"] = "ready" if ok else "failed"
        if 0 < ok < len(toc):
            missing = sorted(
                c["idx"] for c in toc if c["idx"] not in session["ready_segments"]
            )
            print(
                f"[camera] {session_id} partial audio: {ok}/{len(toc)} ready, "
                f"missing chapters {missing}"
            )
    except Exception as exc:
        print(f"[camera] {session_id} audio generation crashed: {exc!r}")
        session["audio_status"] = "ready" if session["ready_segments"] else "failed"


async def _best_toc_for_book(
    book_name: str,
    author: str = "",
    publisher: str = "",
    translator: str = "",
    edition: str = "",
    isbn: str = "",
) -> dict:
    """Best TOC we can get for a book: race the verbatim scrapers (Aladin API +
    Yes24) against an LLM recall and return the winner.

    Shared by /camera/toc-lookup AND /camera/identify so both surfaces get the
    same COMPLETE result. The cover-vision inline TOC is a coarse recall (e.g.
    21 vs the printed 34 chapters), so identify upgrades through here — now that
    audio is instant (pre-generated library) there's no reason to lock in the
    short list. Returns {toc, matched_edition, toc_error, source, verbatim}."""

    async def _do_crawl():
        aladin_res, yes24_res = await asyncio.gather(
            crawl_toc_aladin_api(isbn),
            crawl_toc_for_book(book_name, author, publisher, edition, isbn=isbn),
        )
        aladin_text = (aladin_res or {}).get("toc_text", "")
        yes24_text = (yes24_res or {}).get("toc_text", "")
        if not aladin_text and not yes24_text:
            return None
        recon = reconcile_tocs([
            {"name": "aladin", "label": "알라딘",
             "chapters": _parse_scraped_toc_locally(aladin_text)},
            {"name": "yes24", "label": "Yes24",
             "chapters": _parse_scraped_toc_locally(yes24_text)},
        ])
        if recon["authority"] == "aladin":
            win_text, win_url, win_name = (
                aladin_text, (aladin_res or {}).get("source_url", ""), "aladin"
            )
        else:
            win_text, win_url, win_name = (
                yes24_text, (yes24_res or {}).get("source_url", ""), "yes24"
            )
        chs = await _format_scraped_toc(win_text, book_name, author, publisher)
        if not chs:
            return None
        return (chs, win_url, win_name, recon.get("agreement", ""))

    async def _do_llm():
        return await generate_book_toc(
            book_name, author=author, publisher=publisher,
            translator=translator, edition=edition,
        )

    crawl_task = asyncio.create_task(_do_crawl())
    llm_task = asyncio.create_task(_do_llm())
    try:
        crawl_done, _ = await asyncio.wait({crawl_task}, timeout=20.0)
        if crawl_task in crawl_done and not crawl_task.exception():
            crawler_result = crawl_task.result()
            if crawler_result:
                llm_task.cancel()
                chapters, source_url, source_name, agreement = crawler_result
                store_label = "알라딘 공식 API" if source_name == "aladin" else "Yes24"
                base_note = (
                    agreement
                    or f"{store_label}에서 가져온 실제 목차 ({len(chapters)}장)"
                )
                return {
                    "toc": chapters,
                    "matched_edition": (
                        f"{base_note} · {source_url}" if source_url else base_note
                    ),
                    "toc_error": "",
                    "source": source_name,
                    "verbatim": True,
                }
    except Exception:
        pass
    crawl_task.cancel()
    try:
        toc_result = await llm_task
        return {
            "toc": toc_result.get("chapters", []) or [],
            "matched_edition": toc_result.get("matched_edition", "") or "",
            "toc_error": "",
            "source": "llm",
            "verbatim": False,
        }
    except Exception as exc:
        return {
            "toc": [], "matched_edition": "", "toc_error": str(exc),
            "source": "none", "verbatim": False,
        }


@app.post("/camera/identify")
async def identify_camera_cover(
    photo: UploadFile | None = None,
    source: str = Form(default=""),
    user: dict | None = Depends(current_user),
    x_client_id: str | None = Header(default=None, alias="X-Client-Id"),
    _rl: None = Depends(rate_limit_ai),
) -> dict:
    """Identify a book from its cover photo via Gemini Vision.

    Two sources are supported:
    - Phone/laptop/PC camera: client uploads the image as multipart `photo`
      and sets `source=phone` so we never silently fall back to the XIAO
      board if the upload is missing.
    - XIAO board: client sends `source=board` (no `photo`); backend triggers
      the voicegared server to make the board snap a frame.
    """
    image_bytes = await _resolve_camera_photo(photo, source)
    try:
        cover = await identify_book_from_cover(image_bytes)
    except Exception as exc:
        raise _camera_gemini_http_error(exc, "표지 인식 실패")

    # Single vision call returned chapters inline — no second TOC roundtrip.
    # The vision call's inline TOC is a COARSE recall (e.g. 21 vs the printed
    # 34). Upgrade to the real printed TOC right here so the user never locks
    # in the short list by starting a session before a background fetch lands.
    # Audio is instant now (pre-generated library), so the extra ~10s is fine.
    vision_toc = cover.get("chapters", []) or []
    matched_edition = cover.get("matched_edition", "")
    toc_source = "vision"
    toc_verbatim = False
    if cover.get("title"):
        try:
            best = await _best_toc_for_book(
                cover.get("title", ""), cover.get("author", ""),
                cover.get("publisher", ""), cover.get("translator", ""),
                cover.get("edition", ""), cover.get("isbn", ""),
            )
            best_toc = best.get("toc") or []
            # Adopt the looked-up TOC when it is verbatim (real printed), or
            # simply MORE complete than the vision recall. Never downgrade.
            if best_toc and (best.get("verbatim") or len(best_toc) > len(vision_toc)):
                vision_toc = best_toc
                matched_edition = best.get("matched_edition") or matched_edition
                toc_source = best.get("source", "") or "llm"
                toc_verbatim = bool(best.get("verbatim"))
                print(f"[camera] identify upgraded TOC → {len(best_toc)}장 "
                      f"({toc_source}) for {cover.get('title')!r}")
        except Exception as exc:
            print(f"[camera] identify TOC upgrade failed: {exc!r}")

    return {
        "title": cover.get("title", ""),
        "author": cover.get("author", ""),
        "publisher": cover.get("publisher", ""),
        "translator": cover.get("translator", ""),
        "edition": cover.get("edition", ""),
        "isbn": cover.get("isbn", ""),
        "confidence": cover.get("confidence", 0.0),
        "evidence": cover.get("evidence", ""),
        "matched_edition": matched_edition,
        "toc": vision_toc,
        "toc_source": toc_source,
        "toc_verbatim": toc_verbatim,
        "toc_error": "",
    }


def _camera_gemini_http_error(exc: Exception, prefix: str) -> HTTPException:
    """Map raw Gemini failures to short, actionable Korean messages.

    Quota / billing / expired-key errors hit users a lot during testing —
    they should see what to do next, not a stack trace."""
    msg = str(exc)
    if "RESOURCE_EXHAUSTED" in msg or "429" in msg or "prepayment" in msg.lower():
        return HTTPException(
            503,
            "Gemini API 크레딧이 부족해요. "
            "https://ai.studio/projects 에서 결제 정보를 확인해주세요.",
        )
    if "API_KEY_INVALID" in msg or "API key expired" in msg or "key not valid" in msg.lower():
        return HTTPException(
            503,
            "Gemini API 키가 만료됐어요. "
            "https://aistudio.google.com/apikey 에서 새 키를 만들어 "
            "backend/.env의 GEMINI_API_KEY에 넣고 서버를 재시작해주세요.",
        )
    return HTTPException(503, f"{prefix}: {exc}")


@app.post("/camera/toc-from-photo")
async def camera_toc_from_photo(
    photo: UploadFile | None = None,
    source: str = Form(default=""),
    book_name: str = Form(default=""),
    user: dict | None = Depends(current_user),
    x_client_id: str | None = Header(default=None, alias="X-Client-Id"),
    _rl: None = Depends(rate_limit_ai),
) -> dict:
    """Read the printed 목차 verbatim from a photo of the book's TOC page.

    The most accurate TOC source we have — the page in the reader's hands is
    the ground truth, so this works even for books no bookstore indexes.
    Multi-page TOCs: the client calls this once per photographed page and
    merges the results (the server stays stateless). Same photo sources as
    /camera/identify (multipart upload or the XIAO board)."""
    image_bytes = await _resolve_camera_photo(photo, source)
    try:
        result = await extract_toc_from_photo(image_bytes, book_name)
    except Exception as exc:
        raise _camera_gemini_http_error(exc, "목차 페이지 판독 실패")

    chapters = result.get("chapters") or []
    if not result.get("is_toc_page") or not chapters:
        return {
            "toc": [],
            "matched_edition": "",
            "toc_error": result.get("reason")
            or "목차 페이지를 읽지 못했어요. 목차가 잘 보이게 다시 찍어주세요.",
            "verbatim": False,
            "source": "photo",
        }
    return {
        "toc": chapters,
        "matched_edition": f"책에서 직접 찍은 목차 ({len(chapters)}개 항목)",
        "toc_error": "",
        "verbatim": True,
        "source": "photo",
    }


class TocLookupIn(BaseModel):
    """User-edited cover fields used to re-fetch a more accurate TOC.

    Korean editions are often split into 개정판, 개정 증보 1판, 개정 증보 2판,
    etc., each with a different chapter list — the user fixes whichever field
    the model misread, then we re-run the TOC lookup."""
    book_name: str
    author: str = ""
    publisher: str = ""
    translator: str = ""
    edition: str = ""
    # ISBN read off the cover barcode by /camera/identify — when present it
    # pinpoints the exact edition on Yes24, beating every fuzzy text query.
    isbn: str = ""


@app.post("/camera/toc-lookup")
async def lookup_camera_toc(
    body: TocLookupIn,
    user: dict | None = Depends(current_user),
    x_client_id: str | None = Header(default=None, alias="X-Client-Id"),
    _rl: None = Depends(rate_limit_ai),
) -> dict:
    """Re-fetch the table of contents using user-corrected bibliographic
    fields.

    Because the user explicitly supplied (and is willing to wait for)
    title/author/publisher, we PREFER the real Yes24 product page over an
    LLM-only guess. Fall back to `generate_book_toc` only if the crawl
    finds nothing. Returns the same `{toc, matched_edition, toc_error}`
    shape as the identify endpoint."""
    if not body.book_name.strip():
        raise HTTPException(400, "책 이름을 적어주세요")
    # Same complete-TOC pipeline that /camera/identify now uses, so a manual
    # re-fetch (after the user corrects a field) matches what identify returns.
    return await _best_toc_for_book(
        body.book_name, body.author, body.publisher,
        body.translator, body.edition, body.isbn,
    )


async def _resolve_camera_photo(photo: UploadFile | None, source: str = "") -> bytes:
    """Return JPEG/PNG bytes from either the uploaded multipart field or,
    if `source=board`, from the XIAO board via the voicegared server."""
    has_upload = photo is not None and getattr(photo, "filename", None)
    src = (source or "").strip().lower()
    # Default: if an upload is present, use it. If `source=phone` is set but
    # the upload is missing, fail fast — don't silently try the XIAO server.
    if has_upload:
        data = await photo.read()
        if not data:
            raise HTTPException(400, "사진이 비어있어요")
        if len(data) > 12 * 1024 * 1024:
            raise HTTPException(413, "사진이 너무 커요 (최대 12MB)")
        return data
    if src == "phone":
        raise HTTPException(
            400,
            "카메라 사진이 첨부되지 않았어요. 브라우저 카메라 권한이 허용됐는지 확인하고 다시 찍어주세요.",
        )
    try:
        return await trigger_and_fetch_photo()
    except Exception as exc:
        raise HTTPException(
            503,
            f"{exc}\n\n팁: 노트북/PC 웹캠을 쓰려면 '카메라 소스 = 내 카메라'로 바꿔주세요.",
        )


@app.post("/camera/sessions")
async def create_camera_session(
    body: CameraSessionIn,
    user: dict | None = Depends(current_user),
    x_client_id: str | None = Header(default=None, alias="X-Client-Id"),
    _rl: None = Depends(rate_limit_ai),
) -> dict:
    name = body.book_name.strip()
    if not name:
        raise HTTPException(400, "책 이름을 입력해주세요")

    # If the client already discovered a TOC (via /camera/identify or
    # /camera/toc-lookup), lock it in directly. No regeneration — the user
    # already saw and approved this TOC, and re-running the LLM here just
    # produces a DIFFERENT TOC for the same book, which is confusing.
    toc: list[dict] = []
    matched_edition = body.matched_edition.strip() if body.matched_edition else ""
    if body.toc:
        for i, c in enumerate(body.toc):
            t = c.title.strip()
            if not t:
                continue
            mood = c.mood.strip() or "차분"
            try:
                bpm = max(40, min(160, int(c.bpm or 80)))
            except (ValueError, TypeError):
                bpm = 80
            music = c.music_prompt.strip() or f"ambient instrumental, {mood} mood, {bpm} bpm"
            toc.append({
                "idx": len(toc),
                "title": t[:120],
                "summary": c.summary.strip()[:280],
                "music_prompt": music[:200],
                "bpm": bpm,
                "mood": mood[:30],
            })
            if len(toc) >= MAX_TOC_ENTRIES:
                break

    # Fallback: client didn't send a TOC → generate one now.
    if not toc:
        try:
            toc_result = await generate_book_toc(
                name,
                author=body.author.strip(),
                publisher=body.publisher.strip(),
                translator=body.translator.strip(),
                edition=body.edition.strip(),
            )
        except Exception as exc:
            print(f"[camera] toc generation failed: {exc!r}")
            raise HTTPException(503, f"목차 생성 실패: {exc}")
        toc = toc_result["chapters"]
        if not matched_edition:
            matched_edition = toc_result.get("matched_edition", "")

    # Stable-ish session id: derived from name + uploader + time. Hex only
    # so the regex book-id middleware ignores it cleanly.
    seed = f"{name}|{x_client_id or 'anon'}|{(user or {}).get('email', '')}|{os.urandom(8).hex()}"
    session_id = hashlib.sha256(seed.encode()).hexdigest()[:16]

    session = {
        "id": session_id,
        "book_name": name,
        "author": body.author.strip(),
        "publisher": body.publisher.strip(),
        "translator": body.translator.strip(),
        "edition": body.edition.strip(),
        "matched_edition": matched_edition,
        "toc": toc,
        "current_chapter_idx": 0,
        "audio_status": "pending",
        "ready_segments": [],
        "last_detection": None,
        "detection_locked": False,
        "owner_email": (user or {}).get("email"),
        "owner_client_id": (x_client_id or "").strip() or None,
    }
    CAMERA_SESSIONS[session_id] = session
    asyncio.create_task(
        _generate_camera_audio_background(session_id, toc, lat=body.lat, lon=body.lon)
    )
    return _camera_session_public(session)


@app.get("/camera/sessions/{session_id}")
def get_camera_session(
    session_id: str,
    user: dict | None = Depends(current_user),
    x_client_id: str | None = Header(default=None, alias="X-Client-Id"),
) -> dict:
    s = _require_camera_session(session_id, user, x_client_id)
    return _camera_session_public(s)


class CameraChapterIn(BaseModel):
    chapter_idx: int = Field(ge=0, le=99)


@app.put("/camera/sessions/{session_id}/chapter")
def put_camera_chapter(
    session_id: str,
    body: CameraChapterIn,
    user: dict | None = Depends(current_user),
    x_client_id: str | None = Header(default=None, alias="X-Client-Id"),
) -> dict:
    """Manual chapter override — for testing or when the camera misreads.
    Vision detection writes the same field through /detect."""
    s = _require_camera_session(session_id, user, x_client_id)
    if body.chapter_idx >= len(s["toc"]):
        raise HTTPException(400, "유효하지 않은 챕터 번호예요")
    s["current_chapter_idx"] = body.chapter_idx
    return _camera_session_public(s)


@app.get("/camera/sessions/{session_id}/characters")
async def get_camera_characters(
    session_id: str,
    user: dict | None = Depends(current_user),
    x_client_id: str | None = Header(default=None, alias="X-Client-Id"),
    _rl: None = Depends(rate_limit_ai),
) -> dict:
    """Character cards for the camera session's book — generated once from
    Gemini's knowledge of the title (no book text exists in this flow) and
    cached on the in-memory session."""
    s = _require_camera_session(session_id, user, x_client_id)
    cached = s.get("characters")
    if cached is not None:
        return cached
    try:
        data = await generate_book_characters(
            s["book_name"],
            s.get("author", ""),
            s.get("publisher", ""),
            s.get("toc"),
        )
    except Exception as exc:
        raise HTTPException(503, f"인물 카드 생성 실패: {exc}")
    s["characters"] = data
    return data


@app.post("/camera/sessions/{session_id}/detect")
async def detect_camera_chapter(
    session_id: str,
    photo: UploadFile | None = None,
    source: str = Form(default=""),
    user: dict | None = Depends(current_user),
    x_client_id: str | None = Header(default=None, alias="X-Client-Id"),
    _rl: None = Depends(rate_limit_ai),
) -> dict:
    """Read the page in view and switch the music to the pre-generated library
    track whose MOOD is nearest — continuous page→song matching, no chapter
    mapping. Falls back to the old TOC chapter detection only when the library
    isn't built. Accepts multipart `photo` + `source=phone`, or `source=board`."""
    s = _require_camera_session(session_id, user, x_client_id)
    image_bytes = await _resolve_camera_photo(photo, source)

    import music_library

    if music_library.library_ready():
        try:
            mood = await detect_page_mood(image_bytes)
        except Exception as exc:
            raise HTTPException(503, f"페이지 인식 실패: {exc}")
        # Blank / non-book / unreadable page → HOLD the current track (never
        # yank the music to silence on one bad frame).
        if mood.get("mood_en") or mood.get("mood_ko"):
            query = (
                f"{mood.get('mood_ko','')} {mood.get('mood_en','')} "
                f"tempo {mood.get('bpm', 90)} bpm"
            ).strip()
            track = await music_library.nearest_track_for_mood(
                query, exclude_id=s.get("current_track_id")
            )
            if track:
                s["last_mood"] = mood
                s["audio_status"] = "ready"
                decision = music_library.debounce_track_switch(
                    s.get("current_track_id"),
                    s.get("pending_track_id"),
                    s.get("pending_track_count", 0),
                    track["id"],
                )
                s["current_track_id"] = decision["current_id"]
                s["pending_track_id"] = decision["pending_id"]
                s["pending_track_count"] = decision["pending_count"]
                if decision["switched"]:
                    print(
                        f"[camera] {session_id} page→track: "
                        f"{mood.get('mood_ko')} → {track['id']} "
                        f"({mood.get('evidence','')[:40]})"
                    )
        # Report the CANONICAL mood/bpm of whatever track is actually
        # assigned right now, not the raw per-detection vision read. The
        # live read has its own bpm guess for THIS page and fluctuates read
        # to read (temperature isn't perfectly zero) even while the
        # debounced track correctly stays the same — showing that raw value
        # made the UI's BPM number flicker every ~4s despite the music
        # itself being stable. `evidence` still comes from the live read
        # (it's explaining THIS reading), only mood_ko/bpm are pinned to
        # the actually-playing track.
        playing = music_library.track_by_id(s.get("current_track_id"))
        return {
            "mode": "mood",
            "mood_ko": (playing or {}).get("mood_ko") or mood.get("mood_ko", ""),
            "mood_en": mood.get("mood_en", ""),
            "bpm": (playing or {}).get("bpm") or mood.get("bpm", 90),
            "evidence": mood.get("evidence", ""),
            "track_id": s.get("current_track_id", ""),
            "current_chapter_idx": s.get("current_chapter_idx", -1),
            "audio_status": s["audio_status"],
            "low_quality": mood.get("low_quality", False),
        }

    # ── Legacy fallback: no pre-generated library → TOC chapter detection ──
    current = s["current_chapter_idx"]
    try:
        result = await detect_chapter_from_image(
            s["book_name"], s["toc"], image_bytes, current_idx=current
        )
    except Exception as exc:
        raise HTTPException(503, f"챕터 인식 실패: {exc}")
    s["last_detection"] = result
    # Hysteresis decision: a blurry/ambiguous shot holds the current chapter
    # instead of yanking the music away or dropping it to silence. Switching far
    # needs high confidence; a neighbouring chapter (a page turn) needs less.
    new_idx = decide_chapter(
        current_idx=current,
        detected_idx=result["chapter_idx"],
        confidence=result["confidence"],
        num_chapters=len(s["toc"]),
        low_quality=result.get("low_quality", False),
        locked=s.get("detection_locked", False),
    )
    s["current_chapter_idx"] = new_idx
    # Once we've accepted a real detection, later shots use the stricter
    # "already locked on" gates rather than free initial positioning.
    if new_idx == result["chapter_idx"] and result["chapter_idx"] >= 0:
        s["detection_locked"] = True
    return {
        "mode": "chapter",
        **result,
        "current_chapter_idx": s["current_chapter_idx"],
        "audio_status": s["audio_status"],
        "ready_segments": sorted(s.get("ready_segments", [])),
    }


@app.websocket("/ws/camera/{session_id}")
async def camera_music_ws(ws: WebSocket, session_id: str) -> None:
    """Stream the PCM segment for the session's current chapter, looping.
    Re-checks current_chapter_idx between chunks so a /detect call mid-stream
    switches the audio without dropping the socket."""
    qp = ws.query_params
    token = qp.get("token")
    user = verify_google_token_cached(token) if token else None
    client_id = qp.get("cid")
    try:
        s = _require_camera_session(session_id, user, client_id)
    except HTTPException:
        await ws.close(code=4404)
        return

    await ws.accept()

    # Local import to avoid circular dependency at module load.
    from ws_handler import CHUNK_BYTES, CHUNK_DURATION_S

    stop = asyncio.Event()

    async def receive_ctl() -> None:
        try:
            while not stop.is_set():
                # We don't actually act on client messages — chapter changes
                # arrive via the HTTP endpoint. But we must drain the queue
                # so the socket stays alive.
                msg = await ws.receive_text()
                try:
                    obj = json.loads(msg)
                    if obj.get("type") == "ping":
                        await ws.send_text(json.dumps({"type": "pong"}))
                except Exception:
                    continue
        except WebSocketDisconnect:
            stop.set()
        except Exception:
            stop.set()

    async def send_audio() -> None:
        last_waiting_idx: int | None = None
        while not stop.is_set():
            # New mode: music follows the live page-mood match (current_track_id),
            # not a chapter. Stream that library track on loop and switch the
            # instant a /detect call points current_track_id at a different one.
            track_id = s.get("current_track_id")
            if track_id:
                import music_library

                tpath = music_library.track_path(track_id)
                try:
                    tdata = tpath.read_bytes() if tpath.exists() else b""
                except FileNotFoundError:
                    tdata = b""
                if not tdata:
                    await asyncio.sleep(1.0)
                    continue
                try:
                    await ws.send_text(
                        json.dumps({"type": "track", "track_id": track_id})
                    )
                except Exception:
                    stop.set()
                    return
                offset = 0
                while offset < len(tdata) and not stop.is_set():
                    if s.get("current_track_id") != track_id:
                        break  # a fresh page match switched the track
                    chunk = tdata[offset : offset + CHUNK_BYTES]
                    offset += len(chunk)
                    try:
                        await ws.send_bytes(chunk)
                    except (WebSocketDisconnect, Exception):
                        stop.set()
                        return
                    await asyncio.sleep(CHUNK_DURATION_S)
                continue  # loop the same track, or pick up a switch

            idx = s["current_chapter_idx"]
            path = camera_segment_path(session_id, idx)
            try:
                data = path.read_bytes() if path.exists() else b""
            except FileNotFoundError:
                data = b""
            if not data:
                # This chapter's segment failed or isn't generated yet —
                # play the nearest ready chapter's music instead of silence.
                # Re-checked every loop, so the real segment takes over as
                # soon as it lands on disk.
                ready = sorted(s.get("ready_segments", []))
                if ready:
                    nearest = min(ready, key=lambda r: abs(r - idx))
                    npath = camera_segment_path(session_id, nearest)
                    try:
                        data = npath.read_bytes() if npath.exists() else b""
                    except FileNotFoundError:
                        data = b""
            if not data:
                # Segment not ready yet — tell the UI, wait, retry.
                if last_waiting_idx != idx:
                    try:
                        await ws.send_text(
                            json.dumps(
                                {
                                    "type": "waiting",
                                    "chapter_idx": idx,
                                    "audio_status": s["audio_status"],
                                    "ready_segments": sorted(
                                        s.get("ready_segments", [])
                                    ),
                                }
                            )
                        )
                    except Exception:
                        stop.set()
                        return
                    last_waiting_idx = idx
                await asyncio.sleep(1.5)
                continue

            try:
                await ws.send_text(
                    json.dumps({"type": "chapter", "chapter_idx": idx})
                )
            except Exception:
                stop.set()
                return
            last_waiting_idx = None

            offset = 0
            while offset < len(data) and not stop.is_set():
                if s["current_chapter_idx"] != idx:
                    break
                chunk = data[offset : offset + CHUNK_BYTES]
                offset += len(chunk)
                try:
                    await ws.send_bytes(chunk)
                except WebSocketDisconnect:
                    stop.set()
                    return
                except Exception:
                    stop.set()
                    return
                await asyncio.sleep(CHUNK_DURATION_S)

    receiver = asyncio.create_task(receive_ctl())
    sender = asyncio.create_task(send_audio())
    try:
        done, pending = await asyncio.wait(
            [receiver, sender], return_when=asyncio.FIRST_COMPLETED
        )
        for t in pending:
            t.cancel()
    finally:
        stop.set()
        try:
            await ws.close()
        except Exception:
            pass


@app.websocket("/ws/music/{book_id}")
async def music_ws(ws: WebSocket, book_id: str) -> None:
    # The HTTP middleware doesn't run for WebSockets, so the same access
    # check has to live here. Browsers can't set custom headers on the
    # WebSocket handshake, so we read identity from query params:
    #   ?token=<google-id-token>   (preferred)
    #   ?cid=<anon-client-id>      (fallback for not-signed-in users)
    if not is_valid_book_id(book_id):
        await ws.close(code=4404)
        return
    book = get_book(book_id)
    if not book:
        await ws.close(code=4404)
        return
    qp = ws.query_params
    token = qp.get("token")
    user = verify_google_token_cached(token) if token else None
    client_id = qp.get("cid")
    if not book_visible_to(book, user, client_id):
        # 4403 = custom close code for "access denied"
        await ws.close(code=4403)
        return
    moods = get_moods(book_id)
    if not moods:
        await ws.close(code=4404)
        return
    await ws.accept()
    session = MusicSession(book_id, moods)
    try:
        await session.run(ws)
    except WebSocketDisconnect:
        pass
    except Exception as exc:
        print(f"[main] music_ws error: {exc!r}")
        message = f"{type(exc).__name__}: {exc}"
        try:
            await ws.send_text(json.dumps({"type": "error", "message": message}))
        except Exception:
            pass
        try:
            await ws.close(code=1011)
        except Exception:
            pass


# =========================================================================
# Serve the Vite-built frontend bundle so the whole app runs on a single
# port (8000). This block MUST come last — registering it before the API
# routes would let StaticFiles swallow /books/{id}, /camera/sessions, etc.
# In dev mode (vite dev server on 5173), this mount is a no-op fallback.
# =========================================================================
if FRONTEND_DIST.exists() and (FRONTEND_DIST / "index.html").exists():
    # Mount /assets first so hashed JS/CSS resolve directly.
    if (FRONTEND_DIST / "assets").exists():
        app.mount(
            "/assets",
            StaticFiles(directory=FRONTEND_DIST / "assets"),
            name="frontend-assets",
        )

    def _index_response() -> Response:
        # no-cache: browsers must revalidate index.html on every load, or a
        # cached copy keeps pointing at old hashed bundles after a rebuild
        # (user saw stale JS even after refresh). Hashed /assets stay
        # cacheable — their names change with every build.
        return FileResponse(
            FRONTEND_DIST / "index.html",
            headers={"Cache-Control": "no-cache"},
        )

    @app.get("/")
    def _frontend_index() -> Response:
        return _index_response()

    @app.get("/{full_path:path}")
    def _frontend_catchall(full_path: str) -> Response:
        # Try to serve a literal file (favicon, page-flip.mp3, logo, etc).
        # Otherwise fall back to index.html so the React router can take over.
        # Path-traversal guard: resolve and ensure it stays inside dist/.
        candidate = (FRONTEND_DIST / full_path).resolve()
        try:
            candidate.relative_to(FRONTEND_DIST.resolve())
        except ValueError:
            return _index_response()
        if candidate.is_file():
            return FileResponse(candidate)
        return _index_response()
