import hashlib
import json
import os
from contextlib import asynccontextmanager
from pathlib import Path

import certifi

# macOS python.org builds ship without a system cert bundle, which makes the
# Lyria/Gemini TLS handshakes fail with "unable to get local issuer certificate".
os.environ.setdefault("SSL_CERT_FILE", certifi.where())
os.environ.setdefault("REQUESTS_CA_BUNDLE", certifi.where())

from dotenv import load_dotenv
from fastapi import Depends, FastAPI, Form, Header, HTTPException, Response, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

import asyncio
import shutil

from audio_cache import generate_all_segments
from auth import extract_bearer, verify_google_token
from chapter_summarizer import summarize_all_chapters
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
    get_chapter_summaries,
    get_favorites,
    get_moods,
    get_progress,
    get_reading_stats,
    get_toc,
    init_db,
    list_books,
    list_highlights,
    list_reviews,
    remove_favorite,
    set_audio_status,
    set_chapter_summary,
    set_progress,
    set_toc,
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
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


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
    cover: UploadFile | None = None,
    user: dict | None = Depends(current_user),
) -> dict:
    data = await file.read()
    if not data:
        raise HTTPException(400, "Empty file")

    book_format = _detect_format(file, data)
    book_id = hashlib.sha256(data).hexdigest()[:16]
    desc = (description or "").strip() or None
    cover_png = await _read_cover(cover)

    if book_exists(book_id):
        if desc:
            update_description(book_id, desc)
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
    )
    set_toc(book_id, toc)

    # Kick off Lyria audio generation in the background so future plays stream
    # from the disk cache instead of reopening a Lyria session each time.
    asyncio.create_task(_generate_audio_background(book_id, moods))
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


async def _generate_audio_background(book_id: str, moods: list[dict]) -> None:
    set_audio_status(book_id, "generating")
    try:
        segments = await generate_all_segments(book_id, moods)
        set_audio_status(book_id, "ready", segments)
        print(f"[audio_cache] book {book_id}: {len(segments)} segments ready")
    except Exception as exc:
        print(f"[audio_cache] book {book_id} failed: {exc!r}")
        set_audio_status(book_id, "failed")


@app.get("/books")
def list_all_books(identifier: str = Depends(reader_identifier)) -> dict:
    books = list_books()
    progress_map = get_all_progress(identifier)
    favs = get_favorites(identifier)
    for b in books:
        prog = progress_map.get(b["id"])
        if prog:
            b["last_page"] = prog["page"]
            b["last_read_at"] = prog["updated_at"]
        b["favorited"] = b["id"] in favs
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


@app.get("/books/{book_id}")
def get_book_meta(book_id: str) -> dict:
    book = get_book(book_id)
    if book is None:
        raise HTTPException(404, "Book not found")
    return book


class DescriptionPatch(BaseModel):
    description: str = Field(default="", max_length=4000)


class ReviewIn(BaseModel):
    author: str | None = Field(default=None, max_length=40)
    rating: int = Field(ge=1, le=5)
    text: str = Field(min_length=1, max_length=2000)


@app.patch("/books/{book_id}")
def patch_book(book_id: str, body: DescriptionPatch) -> dict:
    if not book_exists(book_id):
        raise HTTPException(404, "Book not found")
    update_description(book_id, body.description.strip())
    book = get_book(book_id)
    assert book is not None
    return book


@app.delete("/books/{book_id}", status_code=204)
def remove_book(book_id: str, user: dict = Depends(require_user)) -> Response:
    book = get_book(book_id)
    if not book:
        raise HTTPException(404, "Book not found")
    owner = book.get("uploader_email")
    if not owner:
        raise HTTPException(403, "이 책은 로그인 이전에 올라온 책이라 삭제할 수 없어요")
    if owner != user.get("email"):
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
    for sub in ("audio", "tts"):
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
        # Empty page — return a tiny silent WAV so the client doesn't error.
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
async def replace_cover(book_id: str, cover: UploadFile) -> dict:
    if not book_exists(book_id):
        raise HTTPException(404, "Book not found")
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
    return Response(
        content=thumb_path.read_bytes(),
        media_type="image/png",
        headers={"Cache-Control": "public, max-age=86400"},
    )


@app.websocket("/ws/music/{book_id}")
async def music_ws(ws: WebSocket, book_id: str) -> None:
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
