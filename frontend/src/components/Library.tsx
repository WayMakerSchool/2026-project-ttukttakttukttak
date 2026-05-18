import { useEffect, useState } from "react";
import type { AuthUser } from "../lib/auth";

export interface BookSummary {
  id: string;
  title: string;
  page_count: number;
  size_bytes: number;
  uploaded_at: string;
  audio_status?: "pending" | "generating" | "ready" | "failed";
  uploader_email?: string | null;
  uploader_name?: string | null;
}

interface Props {
  user: AuthUser | null;
  onSelectBook: (book: BookSummary) => void;
  onBack: () => void;
  onGoUpload: () => void;
}

function formatDate(iso: string): string {
  try {
    return new Date(iso).toLocaleDateString("ko-KR", {
      year: "numeric",
      month: "short",
      day: "numeric",
    });
  } catch {
    return "";
  }
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export function Library({ user, onSelectBook, onBack, onGoUpload }: Props) {
  const [books, setBooks] = useState<BookSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    async function fetchOnce() {
      try {
        const r = await fetch("/books");
        if (!r.ok) throw new Error(`status ${r.status}`);
        const data = await r.json();
        if (cancelled) return (data.books ?? []) as BookSummary[];
        const list = (data.books ?? []) as BookSummary[];
        setBooks(list);
        setLoading(false);
        return list;
      } catch (e) {
        if (cancelled) return [];
        setError(e instanceof Error ? e.message : String(e));
        setLoading(false);
        return [];
      }
    }

    let intervalId: number | undefined;
    fetchOnce().then((list) => {
      // Poll while any book is still cooking, so the badge flips to "ready"
      // without the user having to refresh manually.
      const anyPending = list.some(
        (b) => b.audio_status === "generating" || b.audio_status === "pending"
      );
      if (anyPending) {
        intervalId = window.setInterval(async () => {
          const updated = await fetchOnce();
          const stillPending = updated.some(
            (b) => b.audio_status === "generating" || b.audio_status === "pending"
          );
          if (!stillPending && intervalId !== undefined) {
            clearInterval(intervalId);
            intervalId = undefined;
          }
        }, 5000);
      }
    });

    return () => {
      cancelled = true;
      if (intervalId !== undefined) clearInterval(intervalId);
    };
  }, []);

  return (
    <section className="library">
      <header className="library-top">
        <button className="reader-brand" onClick={onBack} title="시작 페이지">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <line x1="19" y1="12" x2="5" y2="12" />
            <polyline points="12 19 5 12 12 5" />
          </svg>
          <span>BOOK STORE</span>
        </button>
        <button className="cta cta-compact" onClick={onGoUpload}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <line x1="12" y1="5" x2="12" y2="19" />
            <line x1="5" y1="12" x2="19" y2="12" />
          </svg>
          새 책 올리기
        </button>
      </header>

      <div className="section">
        <div className="section-eyebrow">community library</div>
        <h2 className="section-title">
          모두가 올린 <em>책장.</em>
        </h2>

        {loading && (
          <div className="library-state">
            <div className="spinner" />
            <span>책장을 불러오는 중…</span>
          </div>
        )}

        {!loading && error && (
          <div className="library-state">
            <span className="error">목록을 불러오지 못했어요 ({error})</span>
          </div>
        )}

        {!loading && !error && books.length === 0 && (
          <div className="library-empty">
            <div className="library-empty-illustration">📚</div>
            <p>아직 올라온 책이 없어요.</p>
            <button className="cta" onClick={onGoUpload}>
              첫 책 올리기
            </button>
          </div>
        )}

        {!loading && !error && books.length > 0 && (
          <div className="book-grid">
            {books.map((book) => {
              const status = book.audio_status ?? "pending";
              const cooking = status === "pending" || status === "generating";
              const mine = !!(user && book.uploader_email && user.email === book.uploader_email);
              return (
                <button
                  key={book.id}
                  className={`book-card book-card-${status}${mine ? " book-card-mine" : ""}`}
                  onClick={() => onSelectBook(book)}
                  title={book.title}
                >
                  <div className="book-cover">
                    <img
                      src={`/books/${book.id}/thumb`}
                      alt=""
                      loading="lazy"
                      onError={(e) => {
                        const img = e.currentTarget as HTMLImageElement;
                        img.style.display = "none";
                        img.parentElement?.classList.add("book-cover-fallback");
                      }}
                    />
                    <span className="book-cover-glyph" aria-hidden="true">
                      ♪
                    </span>
                    {cooking && (
                      <div className="book-audio-badge">
                        <div className="spinner" />
                        음악 생성 중
                      </div>
                    )}
                    {status === "failed" && (
                      <div className="book-audio-badge book-audio-badge-failed">
                        음악 생성 실패
                      </div>
                    )}
                    {status === "ready" && (
                      <div className="book-audio-badge book-audio-badge-ready">
                        ♪ ready
                      </div>
                    )}
                    {mine && (
                      <div className="book-mine-badge" title="내가 올린 책">
                        MINE
                      </div>
                    )}
                  </div>
                  <div className="book-info">
                    <div className="book-title">{book.title}</div>
                    <div className="book-meta">
                      {book.page_count}쪽 · {formatSize(book.size_bytes)} ·{" "}
                      {formatDate(book.uploaded_at)}
                    </div>
                  </div>
                </button>
              );
            })}
          </div>
        )}
      </div>
    </section>
  );
}
