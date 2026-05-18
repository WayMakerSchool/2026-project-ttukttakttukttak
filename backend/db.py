import json
import sqlite3
from datetime import datetime, timezone
from pathlib import Path

DB_PATH = Path(__file__).parent / "books.db"


def _connect() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def init_db() -> None:
    with _connect() as conn:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS books (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                page_count INTEGER NOT NULL,
                size_bytes INTEGER NOT NULL,
                uploaded_at TEXT NOT NULL,
                moods_json TEXT NOT NULL,
                audio_status TEXT NOT NULL DEFAULT 'pending',
                audio_segments_json TEXT,
                description TEXT,
                toc_json TEXT,
                uploader_email TEXT,
                uploader_name TEXT
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS reviews (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                book_id TEXT NOT NULL,
                author TEXT,
                rating INTEGER NOT NULL,
                text TEXT NOT NULL,
                created_at TEXT NOT NULL,
                FOREIGN KEY (book_id) REFERENCES books(id) ON DELETE CASCADE
            )
            """
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_reviews_book ON reviews(book_id, created_at DESC)"
        )
        # Idempotent column adds for older DBs.
        for ddl in (
            "ALTER TABLE books ADD COLUMN audio_status TEXT NOT NULL DEFAULT 'pending'",
            "ALTER TABLE books ADD COLUMN audio_segments_json TEXT",
            "ALTER TABLE books ADD COLUMN description TEXT",
            "ALTER TABLE books ADD COLUMN toc_json TEXT",
            "ALTER TABLE books ADD COLUMN uploader_email TEXT",
            "ALTER TABLE books ADD COLUMN uploader_name TEXT",
        ):
            try:
                conn.execute(ddl)
            except sqlite3.OperationalError:
                pass  # already exists


def upsert_book(
    book_id: str,
    title: str,
    page_count: int,
    size_bytes: int,
    moods: list[dict],
    description: str | None = None,
    uploader_email: str | None = None,
    uploader_name: str | None = None,
) -> None:
    with _connect() as conn:
        conn.execute(
            """
            INSERT OR REPLACE INTO books
                (id, title, page_count, size_bytes, uploaded_at,
                 moods_json, audio_status, audio_segments_json, description,
                 uploader_email, uploader_name)
            VALUES (?, ?, ?, ?, ?, ?, 'pending', NULL, ?, ?, ?)
            """,
            (
                book_id,
                title,
                page_count,
                size_bytes,
                datetime.now(timezone.utc).isoformat(),
                json.dumps(moods, ensure_ascii=False),
                description,
                uploader_email,
                uploader_name,
            ),
        )


def delete_book(book_id: str) -> None:
    with _connect() as conn:
        conn.execute("DELETE FROM reviews WHERE book_id = ?", (book_id,))
        conn.execute("DELETE FROM books WHERE id = ?", (book_id,))


def update_description(book_id: str, description: str) -> None:
    with _connect() as conn:
        conn.execute(
            "UPDATE books SET description = ? WHERE id = ?",
            (description, book_id),
        )


def set_audio_status(book_id: str, status: str, segments: list[dict] | None = None) -> None:
    with _connect() as conn:
        conn.execute(
            "UPDATE books SET audio_status = ?, audio_segments_json = ? WHERE id = ?",
            (status, json.dumps(segments, ensure_ascii=False) if segments else None, book_id),
        )


def get_book(book_id: str) -> dict | None:
    with _connect() as conn:
        row = conn.execute(
            """
            SELECT id, title, page_count, size_bytes, uploaded_at,
                   audio_status, description,
                   uploader_email, uploader_name
            FROM books WHERE id = ?
            """,
            (book_id,),
        ).fetchone()
        return dict(row) if row else None


def get_moods(book_id: str) -> list[dict] | None:
    with _connect() as conn:
        row = conn.execute(
            "SELECT moods_json FROM books WHERE id = ?", (book_id,)
        ).fetchone()
        return json.loads(row["moods_json"]) if row else None


def get_audio_segments(book_id: str) -> list[dict] | None:
    with _connect() as conn:
        row = conn.execute(
            "SELECT audio_segments_json FROM books WHERE id = ?", (book_id,)
        ).fetchone()
        if not row or not row["audio_segments_json"]:
            return None
        return json.loads(row["audio_segments_json"])


def set_toc(book_id: str, toc: list[dict]) -> None:
    with _connect() as conn:
        conn.execute(
            "UPDATE books SET toc_json = ? WHERE id = ?",
            (json.dumps(toc, ensure_ascii=False), book_id),
        )


def get_toc(book_id: str) -> list[dict] | None:
    with _connect() as conn:
        row = conn.execute(
            "SELECT toc_json FROM books WHERE id = ?", (book_id,)
        ).fetchone()
        if not row or not row["toc_json"]:
            return None
        try:
            return json.loads(row["toc_json"])
        except (json.JSONDecodeError, TypeError):
            return None


def get_audio_status(book_id: str) -> str | None:
    with _connect() as conn:
        row = conn.execute(
            "SELECT audio_status FROM books WHERE id = ?", (book_id,)
        ).fetchone()
        return row["audio_status"] if row else None


def list_books() -> list[dict]:
    with _connect() as conn:
        rows = conn.execute(
            """
            SELECT b.id, b.title, b.page_count, b.size_bytes, b.uploaded_at,
                   b.audio_status, b.uploader_email, b.uploader_name,
                   (SELECT COUNT(*) FROM reviews r WHERE r.book_id = b.id) AS review_count,
                   (SELECT ROUND(AVG(r.rating), 1) FROM reviews r WHERE r.book_id = b.id) AS rating_avg
            FROM books b
            ORDER BY b.uploaded_at DESC
            """
        ).fetchall()
        return [dict(r) for r in rows]


def book_exists(book_id: str) -> bool:
    with _connect() as conn:
        row = conn.execute(
            "SELECT 1 FROM books WHERE id = ?", (book_id,)
        ).fetchone()
        return row is not None


def add_review(book_id: str, author: str | None, rating: int, text: str) -> dict:
    rating = max(1, min(5, int(rating)))
    author = (author or "").strip() or None
    text = text.strip()
    created = datetime.now(timezone.utc).isoformat()
    with _connect() as conn:
        cur = conn.execute(
            """
            INSERT INTO reviews (book_id, author, rating, text, created_at)
            VALUES (?, ?, ?, ?, ?)
            """,
            (book_id, author, rating, text, created),
        )
        review_id = cur.lastrowid
    return {
        "id": review_id,
        "book_id": book_id,
        "author": author,
        "rating": rating,
        "text": text,
        "created_at": created,
    }


def list_reviews(book_id: str) -> list[dict]:
    with _connect() as conn:
        rows = conn.execute(
            """
            SELECT id, book_id, author, rating, text, created_at
            FROM reviews
            WHERE book_id = ?
            ORDER BY created_at DESC
            """,
            (book_id,),
        ).fetchall()
        return [dict(r) for r in rows]
