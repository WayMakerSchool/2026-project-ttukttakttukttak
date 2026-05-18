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
from fastapi import FastAPI, Form, HTTPException, Response, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

import asyncio

from audio_cache import generate_all_segments
from db import (
    add_review,
    book_exists,
    get_book,
    get_moods,
    init_db,
    list_books,
    list_reviews,
    set_audio_status,
    update_description,
    upsert_book,
)
from mood_analyzer import analyze_pages
from pdf_parser import extract_pages, normalize_cover_image, render_thumbnail
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


@app.get("/health")
def health() -> dict:
    return {"ok": True, "books": len(list_books())}


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


@app.post("/upload")
async def upload_book(
    file: UploadFile,
    description: str | None = Form(default=None),
    title_override: str | None = Form(default=None),
    cover: UploadFile | None = None,
) -> dict:
    if file.content_type not in {"application/pdf", "application/octet-stream"}:
        raise HTTPException(400, "Only PDF files are accepted")
    data = await file.read()
    if not data:
        raise HTTPException(400, "Empty file")

    book_id = hashlib.sha256(data).hexdigest()[:16]
    desc = (description or "").strip() or None
    cover_png = await _read_cover(cover)

    if book_exists(book_id):
        # If the uploader added a description / cover for an already-known
        # book, persist those so the second submitter's input isn't lost.
        if desc:
            update_description(book_id, desc)
        if cover_png is not None:
            (STORAGE / f"{book_id}.thumb.png").write_bytes(cover_png)
        book = get_book(book_id)
        assert book is not None
        return {"book_id": book_id, "page_count": book["page_count"], "cached": True}

    pages = extract_pages(data)
    if not pages:
        raise HTTPException(400, "PDF has no pages")
    moods = await analyze_pages(pages)

    pdf_path = STORAGE / f"{book_id}.pdf"
    pdf_path.write_bytes(data)

    # Cover: user-supplied wins, otherwise auto-render from PDF first page.
    thumb_path = STORAGE / f"{book_id}.thumb.png"
    if cover_png is not None:
        thumb_path.write_bytes(cover_png)
    else:
        try:
            thumb_path.write_bytes(render_thumbnail(data))
        except Exception as exc:
            print(f"[main] thumbnail render failed: {exc!r}")

    title = ((title_override or file.filename) or f"untitled-{book_id}.pdf").strip()
    upsert_book(book_id, title, len(pages), len(data), moods, description=desc)

    # Kick off Lyria audio generation in the background so future plays stream
    # from the disk cache instead of reopening a Lyria session each time.
    asyncio.create_task(_generate_audio_background(book_id, moods))

    return {"book_id": book_id, "page_count": len(pages), "cached": False}


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
def list_all_books() -> dict:
    return {"books": list_books()}


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


@app.get("/books/{book_id}/moods")
def get_book_moods(book_id: str) -> dict:
    moods = get_moods(book_id)
    if moods is None:
        raise HTTPException(404, "Book not found")
    return {"moods": moods}


@app.get("/books/{book_id}/pdf")
def get_pdf(book_id: str) -> Response:
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
