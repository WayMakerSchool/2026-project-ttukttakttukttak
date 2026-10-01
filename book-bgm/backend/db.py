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
            "ALTER TABLE books ADD COLUMN characters_json TEXT",
            "ALTER TABLE books ADD COLUMN author TEXT",
            "ALTER TABLE books ADD COLUMN category TEXT",
            "ALTER TABLE books ADD COLUMN subtitle TEXT",
            "ALTER TABLE books ADD COLUMN translator TEXT",
            "ALTER TABLE books ADD COLUMN publisher TEXT",
            "ALTER TABLE books ADD COLUMN published_year INTEGER",
            "ALTER TABLE books ADD COLUMN language TEXT",
            "ALTER TABLE books ADD COLUMN isbn TEXT",
            "ALTER TABLE books ADD COLUMN series_name TEXT",
            "ALTER TABLE books ADD COLUMN series_index TEXT",
            "ALTER TABLE books ADD COLUMN tags_json TEXT",
            "ALTER TABLE books ADD COLUMN visibility TEXT NOT NULL DEFAULT 'private'",
            "ALTER TABLE books ADD COLUMN uploader_client_id TEXT",
            "ALTER TABLE books ADD COLUMN image_style_json TEXT",
            # Copyright warranty timestamp — set at upload time when the
            # uploader checks the "본인이 권리를 가진 책" box. Used as
            # evidence in any later DMCA-style dispute.
            "ALTER TABLE books ADD COLUMN consent_accepted_at TEXT",
        ):
            try:
                conn.execute(ddl)
            except sqlite3.OperationalError:
                pass  # already exists

        # One-time grandfather migration: when the visibility column was just
        # added, every existing row was backfilled with the column DEFAULT
        # ('private'). Books uploaded before the security feature shipped
        # weren't tagged with an `uploader_client_id`, so those owners can't
        # match themselves anymore and effectively lose access. Flip those
        # to 'public' so they keep working. Tracked via PRAGMA user_version
        # so this runs exactly once per DB.
        current_version = conn.execute("PRAGMA user_version").fetchone()[0]
        if current_version < 1:
            conn.execute(
                "UPDATE books SET visibility = 'public' "
                "WHERE uploader_client_id IS NULL"
            )
            conn.execute("PRAGMA user_version = 1")


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
    author: str | None = None,
    category: str | None = None,
    subtitle: str | None = None,
    translator: str | None = None,
    publisher: str | None = None,
    published_year: int | None = None,
    language: str | None = None,
    isbn: str | None = None,
    series_name: str | None = None,
    series_index: str | None = None,
    tags: list[str] | None = None,
    visibility: str = "private",
    uploader_client_id: str | None = None,
    consent_accepted_at: str | None = None,
) -> None:
    tags_json = json.dumps(tags, ensure_ascii=False) if tags else None
    if visibility not in ("private", "public"):
        visibility = "private"
    with _connect() as conn:
        conn.execute(
            """
            INSERT OR REPLACE INTO books
                (id, title, page_count, size_bytes, uploaded_at,
                 moods_json, audio_status, audio_segments_json, description,
                 uploader_email, uploader_name, format, author, category,
                 subtitle, translator, publisher, published_year, language,
                 isbn, series_name, series_index, tags_json,
                 visibility, uploader_client_id, consent_accepted_at)
            VALUES (?, ?, ?, ?, ?, ?, 'pending', NULL, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                author,
                category,
                subtitle,
                translator,
                publisher,
                published_year,
                language,
                isbn,
                series_name,
                series_index,
                tags_json,
                visibility,
                uploader_client_id,
                consent_accepted_at,
            ),
        )


_METADATA_FIELDS = (
    "title",
    "author",
    "category",
    "description",
    "subtitle",
    "translator",
    "publisher",
    "published_year",
    "language",
    "isbn",
    "series_name",
    "series_index",
)


def update_book_metadata(
    book_id: str,
    **kwargs,
) -> None:
    """Idempotent partial update of editable book metadata.

    Accepted kwargs: title, author, category, description, subtitle,
    translator, publisher, published_year, language, isbn, series_name,
    series_index, tags (list[str]). `None` values are skipped (not cleared);
    pass empty string to clear a text field."""
    fields: list[str] = []
    values: list = []
    for name in _METADATA_FIELDS:
        if name in kwargs and kwargs[name] is not None:
            fields.append(f"{name} = ?")
            values.append(kwargs[name])
    if "tags" in kwargs and kwargs["tags"] is not None:
        fields.append("tags_json = ?")
        values.append(json.dumps(kwargs["tags"], ensure_ascii=False))
    if not fields:
        return
    values.append(book_id)
    with _connect() as conn:
        conn.execute(
            f"UPDATE books SET {', '.join(fields)} WHERE id = ?",
            values,
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
                   uploader_email, uploader_name, format,
                   author, category, subtitle, translator, publisher,
                   published_year, language, isbn, series_name, series_index,
                   tags_json, visibility, uploader_client_id
            FROM books WHERE id = ?
            """,
            (book_id,),
        ).fetchone()
        if row is None:
            return None
        d = dict(row)
        raw_tags = d.pop("tags_json", None)
        try:
            d["tags"] = json.loads(raw_tags) if raw_tags else []
        except (json.JSONDecodeError, TypeError):
            d["tags"] = []
        return d


def set_book_visibility(book_id: str, visibility: str) -> None:
    if visibility not in ("private", "public"):
        raise ValueError("visibility must be 'private' or 'public'")
    with _connect() as conn:
        conn.execute(
            "UPDATE books SET visibility = ? WHERE id = ?",
            (visibility, book_id),
        )


def get_book_image_style(book_id: str) -> dict | None:
    """The per-book art-direction sheet used to keep chapter images
    visually consistent. Returns None if it hasn't been generated yet."""
    with _connect() as conn:
        row = conn.execute(
            "SELECT image_style_json FROM books WHERE id = ?", (book_id,)
        ).fetchone()
    if not row or not row["image_style_json"]:
        return None
    try:
        return json.loads(row["image_style_json"])
    except json.JSONDecodeError:
        return None


def set_book_image_style(book_id: str, style: dict) -> None:
    with _connect() as conn:
        conn.execute(
            "UPDATE books SET image_style_json = ? WHERE id = ?",
            (json.dumps(style, ensure_ascii=False), book_id),
        )


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
        today_row = conn.execute(
            """
            SELECT COALESCE(SUM(seconds), 0) AS today_seconds
            FROM reading_sessions
            WHERE identifier = ? AND date(started_at) = date('now')
            """,
            (identifier,),
        ).fetchone()
        # Distinct days the user read, most-recent first.
        day_rows = conn.execute(
            """
            SELECT DISTINCT date(started_at) AS d
            FROM reading_sessions
            WHERE identifier = ?
            ORDER BY d DESC
            """,
            (identifier,),
        ).fetchall()

    # Streak: consecutive days ending today or yesterday (give 1-day grace).
    from datetime import date, timedelta

    streak = 0
    today = date.today()
    expected = today
    days_set = {r["d"] for r in day_rows}
    if today.isoformat() not in days_set and (today - timedelta(days=1)).isoformat() in days_set:
        expected = today - timedelta(days=1)
    while expected.isoformat() in days_set:
        streak += 1
        expected -= timedelta(days=1)

    # ===== Extended stats (dashboard) =====
    with _connect() as conn:
        # Daily breakdown for the last 30 days (zero-filled by frontend).
        daily_rows = conn.execute(
            """
            SELECT date(started_at) AS d, SUM(seconds) AS s
            FROM reading_sessions
            WHERE identifier = ? AND started_at >= datetime('now', '-29 days')
            GROUP BY date(started_at)
            ORDER BY d ASC
            """,
            (identifier,),
        ).fetchall()
        daily = [{"date": r["d"], "seconds": int(r["s"] or 0)} for r in daily_rows]

        # Last 90 days heatmap.
        hm_rows = conn.execute(
            """
            SELECT date(started_at) AS d, SUM(seconds) AS s
            FROM reading_sessions
            WHERE identifier = ? AND started_at >= datetime('now', '-89 days')
            GROUP BY date(started_at)
            ORDER BY d ASC
            """,
            (identifier,),
        ).fetchall()
        heatmap = [{"date": r["d"], "seconds": int(r["s"] or 0)} for r in hm_rows]

        # Top books by accumulated time.
        top_rows = conn.execute(
            """
            SELECT rs.book_id,
                   SUM(rs.seconds) AS s,
                   b.title,
                   b.page_count,
                   b.author,
                   (SELECT page FROM reading_progress p
                    WHERE p.book_id = rs.book_id AND p.identifier = ?) AS last_page
            FROM reading_sessions rs
            LEFT JOIN books b ON b.id = rs.book_id
            WHERE rs.identifier = ?
            GROUP BY rs.book_id
            ORDER BY s DESC
            LIMIT 8
            """,
            (identifier, identifier),
        ).fetchall()
        top_books = [
            {
                "book_id": r["book_id"],
                "title": r["title"] or "(삭제된 책)",
                "author": r["author"] or "",
                "seconds": int(r["s"] or 0),
                "page_count": int(r["page_count"] or 0),
                "last_page": int(r["last_page"] or 0),
            }
            for r in top_rows
        ]

        # Best single day.
        best_row = conn.execute(
            """
            SELECT date(started_at) AS d, SUM(seconds) AS s
            FROM reading_sessions
            WHERE identifier = ?
            GROUP BY date(started_at)
            ORDER BY s DESC
            LIMIT 1
            """,
            (identifier,),
        ).fetchone()
        best_day = (
            {"date": best_row["d"], "seconds": int(best_row["s"] or 0)}
            if best_row and best_row["s"]
            else None
        )

        # Avg session.
        session_count_row = conn.execute(
            "SELECT COUNT(*) AS n FROM reading_sessions WHERE identifier = ?",
            (identifier,),
        ).fetchone()
        n_sessions = int(session_count_row["n"] or 0)
        total_sec = int(row["total_seconds"] or 0)
        avg_session = int(total_sec / n_sessions) if n_sessions else 0

        # Previous week (days -14 .. -7) to compute week-over-week %.
        prev_row = conn.execute(
            """
            SELECT COALESCE(SUM(seconds), 0) AS s
            FROM reading_sessions
            WHERE identifier = ?
                AND started_at >= datetime('now', '-14 days')
                AND started_at < datetime('now', '-7 days')
            """,
            (identifier,),
        ).fetchone()
        prev_week_sec = int(prev_row["s"] or 0)

        # Monthly aggregate for last 6 months.
        month_rows = conn.execute(
            """
            SELECT strftime('%Y-%m', started_at) AS m, SUM(seconds) AS s
            FROM reading_sessions
            WHERE identifier = ? AND started_at >= datetime('now', '-180 days')
            GROUP BY m
            ORDER BY m ASC
            """,
            (identifier,),
        ).fetchall()
        monthly = [{"month": r["m"], "seconds": int(r["s"] or 0)} for r in month_rows]

    return {
        "books": int(row["books"] or 0),
        "total_seconds": total_sec,
        "week_seconds": int(week_row["week_seconds"] or 0),
        "today_seconds": int(today_row["today_seconds"] or 0),
        "streak_days": streak,
        "read_dates": [r["d"] for r in day_rows[:30]],
        # extended
        "daily": daily,
        "heatmap": heatmap,
        "monthly": monthly,
        "top_books": top_books,
        "best_day": best_day,
        "avg_session_seconds": avg_session,
        "session_count": n_sessions,
        "prev_week_seconds": prev_week_sec,
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
                   b.author, b.category, b.subtitle, b.publisher,
                   b.published_year, b.language, b.series_name, b.series_index,
                   b.tags_json, b.visibility, b.uploader_client_id,
                   (SELECT COUNT(*) FROM reviews r WHERE r.book_id = b.id) AS review_count,
                   (SELECT ROUND(AVG(r.rating), 1) FROM reviews r WHERE r.book_id = b.id) AS rating_avg
            FROM books b
            ORDER BY b.uploaded_at DESC
            """
        ).fetchall()
        out: list[dict] = []
        for r in rows:
            d = dict(r)
            raw_tags = d.pop("tags_json", None)
            try:
                d["tags"] = json.loads(raw_tags) if raw_tags else []
            except (json.JSONDecodeError, TypeError):
                d["tags"] = []
            out.append(d)
        return out


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


def set_characters(book_id: str, data: dict) -> None:
    with _connect() as conn:
        conn.execute(
            "UPDATE books SET characters_json = ? WHERE id = ?",
            (json.dumps(data, ensure_ascii=False), book_id),
        )


def get_characters(book_id: str) -> dict | None:
    with _connect() as conn:
        row = conn.execute(
            "SELECT characters_json FROM books WHERE id = ?", (book_id,)
        ).fetchone()
    if not row or not row["characters_json"]:
        return None
    try:
        return json.loads(row["characters_json"])
    except (json.JSONDecodeError, TypeError):
        return None


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
