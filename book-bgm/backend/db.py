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
                uploader_name TEXT,
                format TEXT NOT NULL DEFAULT 'pdf'
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
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS reading_progress (
                book_id TEXT NOT NULL,
                identifier TEXT NOT NULL,
                page INTEGER NOT NULL,
                updated_at TEXT NOT NULL,
                PRIMARY KEY (book_id, identifier)
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS highlights (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                book_id TEXT NOT NULL,
                identifier TEXT NOT NULL,
                page INTEGER NOT NULL,
                text TEXT NOT NULL,
                note TEXT,
                color TEXT,
                created_at TEXT NOT NULL,
                FOREIGN KEY (book_id) REFERENCES books(id) ON DELETE CASCADE
            )
            """
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_highlights ON highlights(book_id, identifier, page)"
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS chapter_summaries (
                book_id TEXT NOT NULL,
                page INTEGER NOT NULL,
                summary TEXT NOT NULL,
                created_at TEXT NOT NULL,
                PRIMARY KEY (book_id, page),
                FOREIGN KEY (book_id) REFERENCES books(id) ON DELETE CASCADE
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS favorites (
                identifier TEXT NOT NULL,
                book_id TEXT NOT NULL,
                created_at TEXT NOT NULL,
                PRIMARY KEY (identifier, book_id),
                FOREIGN KEY (book_id) REFERENCES books(id) ON DELETE CASCADE
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS reading_sessions (
                identifier TEXT NOT NULL,
                book_id TEXT NOT NULL,
                started_at TEXT NOT NULL,
                seconds INTEGER NOT NULL,
                PRIMARY KEY (identifier, book_id, started_at),
                FOREIGN KEY (book_id) REFERENCES books(id) ON DELETE CASCADE
            )
            """
        )
        # Idempotent column adds for older DBs.
        for ddl in (
            "ALTER TABLE books ADD COLUMN audio_status TEXT NOT NULL DEFAULT 'pending'",
            "ALTER TABLE books ADD COLUMN audio_segments_json TEXT",
            "ALTER TABLE books ADD COLUMN description TEXT",
            "ALTER TABLE books ADD COLUMN toc_json TEXT",
            "ALTER TABLE books ADD COLUMN uploader_email TEXT",
            "ALTER TABLE books ADD COLUMN uploader_name TEXT",
            "ALTER TABLE books ADD COLUMN format TEXT NOT NULL DEFAULT 'pdf'",
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
    book_format: str = "pdf",
) -> None:
    with _connect() as conn:
        conn.execute(
            """
            INSERT OR REPLACE INTO books
                (id, title, page_count, size_bytes, uploaded_at,
                 moods_json, audio_status, audio_segments_json, description,
                 uploader_email, uploader_name, format)
            VALUES (?, ?, ?, ?, ?, ?, 'pending', NULL, ?, ?, ?, ?)
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
                book_format,
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
                   uploader_email, uploader_name, format
            FROM books WHERE id = ?
            """,
            (book_id,),
        ).fetchone()
        return dict(row) if row else None


def add_favorite(identifier: str, book_id: str) -> None:
    with _connect() as conn:
        conn.execute(
            """
            INSERT OR IGNORE INTO favorites (identifier, book_id, created_at)
            VALUES (?, ?, ?)
            """,
            (identifier, book_id, datetime.now(timezone.utc).isoformat()),
        )


def remove_favorite(identifier: str, book_id: str) -> None:
    with _connect() as conn:
        conn.execute(
            "DELETE FROM favorites WHERE identifier = ? AND book_id = ?",
            (identifier, book_id),
        )


def get_favorites(identifier: str) -> set[str]:
    with _connect() as conn:
        rows = conn.execute(
            "SELECT book_id FROM favorites WHERE identifier = ?", (identifier,)
        ).fetchall()
    return {r["book_id"] for r in rows}


def add_reading_session(identifier: str, book_id: str, seconds: int) -> None:
    with _connect() as conn:
        conn.execute(
            """
            INSERT INTO reading_sessions (identifier, book_id, started_at, seconds)
            VALUES (?, ?, ?, ?)
            """,
            (identifier, book_id, datetime.now(timezone.utc).isoformat(), seconds),
        )


def get_reading_stats(identifier: str) -> dict:
    with _connect() as conn:
        row = conn.execute(
            """
            SELECT
                COUNT(DISTINCT book_id) AS books,
                COALESCE(SUM(seconds), 0) AS total_seconds
            FROM reading_sessions
            WHERE identifier = ?
            """,
            (identifier,),
        ).fetchone()
        week_row = conn.execute(
            """
            SELECT COALESCE(SUM(seconds), 0) AS week_seconds
            FROM reading_sessions
            WHERE identifier = ? AND started_at >= datetime('now', '-7 days')
            """,
            (identifier,),
        ).fetchone()
    return {
        "books": int(row["books"] or 0),
        "total_seconds": int(row["total_seconds"] or 0),
        "week_seconds": int(week_row["week_seconds"] or 0),
    }


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
                   b.audio_status, b.uploader_email, b.uploader_name, b.format,
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


def set_progress(book_id: str, identifier: str, page: int) -> None:
    with _connect() as conn:
        conn.execute(
            """
            INSERT INTO reading_progress (book_id, identifier, page, updated_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(book_id, identifier)
            DO UPDATE SET page = excluded.page, updated_at = excluded.updated_at
            """,
            (book_id, identifier, page, datetime.now(timezone.utc).isoformat()),
        )


def get_progress(book_id: str, identifier: str) -> dict | None:
    with _connect() as conn:
        row = conn.execute(
            "SELECT page, updated_at FROM reading_progress WHERE book_id = ? AND identifier = ?",
            (book_id, identifier),
        ).fetchone()
        return dict(row) if row else None


def get_all_progress(identifier: str) -> dict[str, dict]:
    """Map book_id -> {page, updated_at} for everything `identifier` has read."""
    with _connect() as conn:
        rows = conn.execute(
            "SELECT book_id, page, updated_at FROM reading_progress WHERE identifier = ?",
            (identifier,),
        ).fetchall()
    return {r["book_id"]: {"page": r["page"], "updated_at": r["updated_at"]} for r in rows}


def add_highlight(
    book_id: str,
    identifier: str,
    page: int,
    text: str,
    note: str | None,
    color: str | None,
) -> dict:
    created = datetime.now(timezone.utc).isoformat()
    with _connect() as conn:
        cur = conn.execute(
            """
            INSERT INTO highlights (book_id, identifier, page, text, note, color, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            (book_id, identifier, page, text, note, color, created),
        )
        hid = cur.lastrowid
    return {
        "id": hid,
        "book_id": book_id,
        "identifier": identifier,
        "page": page,
        "text": text,
        "note": note,
        "color": color,
        "created_at": created,
    }


def list_highlights(book_id: str, identifier: str) -> list[dict]:
    with _connect() as conn:
        rows = conn.execute(
            """
            SELECT id, book_id, page, text, note, color, created_at
            FROM highlights
            WHERE book_id = ? AND identifier = ?
            ORDER BY page ASC, id ASC
            """,
            (book_id, identifier),
        ).fetchall()
        return [dict(r) for r in rows]


def delete_highlight(highlight_id: int, identifier: str) -> bool:
    with _connect() as conn:
        cur = conn.execute(
            "DELETE FROM highlights WHERE id = ? AND identifier = ?",
            (highlight_id, identifier),
        )
        return cur.rowcount > 0


def set_chapter_summary(book_id: str, page: int, summary: str) -> None:
    with _connect() as conn:
        conn.execute(
            """
            INSERT INTO chapter_summaries (book_id, page, summary, created_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(book_id, page) DO UPDATE SET summary = excluded.summary
            """,
            (book_id, page, summary, datetime.now(timezone.utc).isoformat()),
        )


def get_chapter_summaries(book_id: str) -> dict[int, str]:
    with _connect() as conn:
        rows = conn.execute(
            "SELECT page, summary FROM chapter_summaries WHERE book_id = ?",
            (book_id,),
        ).fetchall()
    return {int(r["page"]): r["summary"] for r in rows}


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
