import { useEffect, useState } from "react";
import { authHeaders, type AuthUser } from "../lib/auth";
import { progressHeaders } from "../lib/client-id";

export interface BookSummary {
  id: string;
  title: string;
  page_count: number;
  size_bytes: number;
  uploaded_at: string;
  audio_status?: "pending" | "generating" | "ready" | "failed";
  uploader_email?: string | null;
  uploader_name?: string | null;
  last_page?: number;
  last_read_at?: string;
  favorited?: boolean;
  rating_avg?: number | null;
  review_count?: number;
  format?: "pdf" | "epub";
}

type SortBy = "recent" | "rating" | "progress" | "title";

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
  const [sortBy, setSortBy] = useState<SortBy>("recent");
  const [onlyFavs, setOnlyFavs] = useState(false);

  async function toggleFavorite(book: BookSummary, e: React.MouseEvent) {
    e.stopPropagation();
    const method = book.favorited ? "DELETE" : "POST";
    setBooks((prev) =>
      prev.map((b) => (b.id === book.id ? { ...b, favorited: !b.favorited } : b))
    );
    try {
      await fetch(`/books/${book.id}/favorite`, {
        method,
        headers: { ...progressHeaders(), ...authHeaders(user) },
      });
    } catch {
      // Revert on error.
      setBooks((prev) =>
        prev.map((b) =>
          b.id === book.id ? { ...b, favorited: book.favorited } : b
        )
      );
    }
  }

  const visible = books
    .filter((b) => (onlyFavs ? b.favorited : true))
    .slice()
    .sort((a, b) => {
      switch (sortBy) {
        case "rating":
          return (b.rating_avg ?? 0) - (a.rating_avg ?? 0);
        case "progress": {
          const pa = (a.last_page ?? 0) / Math.max(1, a.page_count);
          const pb = (b.last_page ?? 0) / Math.max(1, b.page_count);
          return pb - pa;
        }
        case "title":
          return a.title.localeCompare(b.title, "ko");
        case "recent":
        default:
          return (
            new Date(b.uploaded_at).getTime() -
            new Date(a.uploaded_at).getTime()
          );
      }
    });

  useEffect(() => {
    let cancelled = false;

    async function fetchOnce() {
      try {
        const r = await fetch("/books", {
          headers: { ...progressHeaders(), ...authHeaders(user) },
        });
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
  }, [user]);

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
          <>
            <div className="library-controls">
              <div className="library-filters">
                <button
                  className={`chip${!onlyFavs ? " chip-active" : ""}`}
                  type="button"
                  onClick={() => setOnlyFavs(false)}
                >
                  전체 · {books.length}
                </button>
                <button
                  className={`chip${onlyFavs ? " chip-active" : ""}`}
                  type="button"
                  onClick={() => setOnlyFavs(true)}
                >
                  ♥ 즐겨찾기 · {books.filter((b) => b.favorited).length}
                </button>
              </div>
              <select
                className="library-sort"
                value={sortBy}
                onChange={(e) => setSortBy(e.target.value as SortBy)}
              >
                <option value="recent">최신순</option>
                <option value="rating">별점순</option>
                <option value="progress">읽은 정도</option>
                <option value="title">제목 순</option>
              </select>
            </div>
            <div className="book-grid">
              {visible.map((book) => {
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
                    <button
                      type="button"
                      className={`book-fav${book.favorited ? " book-fav-on" : ""}`}
                      onClick={(e) => toggleFavorite(book, e)}
                      aria-label={book.favorited ? "즐겨찾기 해제" : "즐겨찾기"}
                      title={book.favorited ? "즐겨찾기 해제" : "즐겨찾기"}
                    >
                      {book.favorited ? "♥" : "♡"}
                    </button>
                  </div>
                  <div className="book-info">
                    <div className="book-title">{book.title}</div>
                    <div className="book-meta">
                      {book.page_count}쪽 · {formatSize(book.size_bytes)} ·{" "}
                      {formatDate(book.uploaded_at)}
                    </div>
                    {typeof book.last_page === "number" && book.last_page > 1 && (
                      <div className="book-progress">
                        <div className="book-progress-bar">
                          <div
                            className="book-progress-fill"
                            style={{
                              width: `${Math.min(100, Math.round((book.last_page / book.page_count) * 100))}%`,
                            }}
                          />
                        </div>
                        <span className="book-progress-label">
                          {book.last_page}쪽까지 읽음
                        </span>
                      </div>
                    )}
                  </div>
                </button>
              );
            })}
            </div>
          </>
        )}
      </div>
    </section>
  );
}
