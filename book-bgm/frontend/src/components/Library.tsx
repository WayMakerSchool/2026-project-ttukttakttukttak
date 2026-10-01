import { useEffect, useMemo, useState } from "react";
import { authHeaders, type AuthUser } from "../lib/auth";
import { authQuery, progressHeaders } from "../lib/client-id";
import { StatsDashboard } from "./StatsDashboard";

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
  // Extended metadata
  author?: string | null;
  category?: string | null;
  subtitle?: string | null;
  publisher?: string | null;
  published_year?: number | null;
  language?: string | null;
  series_name?: string | null;
  series_index?: string | null;
  description?: string | null;
  translator?: string | null;
  isbn?: string | null;
  tags?: string[];
}

function highlightMatch(text: string, q: string): React.ReactNode {
  if (!q) return text;
  const lc = text.toLowerCase();
  const qlc = q.toLowerCase();
  const out: React.ReactNode[] = [];
  let i = 0;
  while (i < text.length) {
    const idx = lc.indexOf(qlc, i);
    if (idx < 0) {
      out.push(text.slice(i));
      break;
    }
    if (idx > i) out.push(text.slice(i, idx));
    out.push(
      <mark className="lib-hit" key={`${idx}-${i}`}>
        {text.slice(idx, idx + q.length)}
      </mark>
    );
    i = idx + q.length;
  }
  return out;
}

function scoreBook(b: BookSummary, q: string): number {
  if (!q) return 0;
  const lc = q.toLowerCase();
  let score = 0;
  const hit = (text: string | null | undefined, weight: number) => {
    if (!text) return;
    const i = text.toLowerCase().indexOf(lc);
    if (i >= 0) {
      score += weight;
      if (i === 0) score += weight * 0.4; // prefix bonus
    }
  };
  hit(b.title, 5);
  hit(b.subtitle, 3.5);
  hit(b.author, 3);
  hit(b.series_name, 2.5);
  hit(b.translator, 1.5);
  hit(b.category, 2);
  hit(b.publisher, 1.5);
  hit(b.description, 0.8);
  hit(b.isbn, 6);
  hit(b.uploader_name, 1.2);
  for (const t of b.tags ?? []) hit(t, 2.5);
  return score;
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

interface StatsData {
  books: number;
  total_seconds: number;
  today_seconds: number;
  streak_days: number;
}

export function Library({ user, onSelectBook, onBack, onGoUpload }: Props) {
  const [books, setBooks] = useState<BookSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [sortBy, setSortBy] = useState<SortBy>("recent");
  const [onlyFavs, setOnlyFavs] = useState(false);
  const [query, setQuery] = useState("");
  const [activeCategory, setActiveCategory] = useState<string | null>(null);
  const [activeTag, setActiveTag] = useState<string | null>(null);
  const [stats, setStats] = useState<StatsData | null>(null);
  const [statsOpen, setStatsOpen] = useState(false);
  const [dailyGoal, setDailyGoal] = useState<number>(() => {
    const raw = parseInt(localStorage.getItem("book_store_daily_goal") ?? "30", 10);
    return isNaN(raw) ? 30 : Math.max(5, Math.min(240, raw));
  });

  function changeGoal() {
    const input = window.prompt(
      "오늘 목표 (분) — 5분~240분 사이로 입력하세요",
      String(dailyGoal)
    );
    if (!input) return;
    const n = parseInt(input, 10);
    if (isNaN(n) || n < 5 || n > 240) return;
    setDailyGoal(n);
    localStorage.setItem("book_store_daily_goal", String(n));
  }

  useEffect(() => {
    let cancelled = false;
    fetch("/me/stats", {
      headers: { ...progressHeaders(), ...authHeaders(user) },
    })
      .then((r) => (r.ok ? r.json() : null))
      .then((d) => {
        if (cancelled) return;
        setStats(d);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [user]);

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

  const trimmedQuery = query.trim();

  // Derive category list with counts, sorted by frequency.
  const categoryCounts = useMemo(() => {
    const m = new Map<string, number>();
    for (const b of books) {
      if (b.category) m.set(b.category, (m.get(b.category) ?? 0) + 1);
    }
    return Array.from(m.entries()).sort((a, b) => b[1] - a[1]);
  }, [books]);

  // Top tags across the library (for the secondary filter row).
  const topTags = useMemo(() => {
    const m = new Map<string, number>();
    for (const b of books) {
      for (const t of b.tags ?? []) m.set(t, (m.get(t) ?? 0) + 1);
    }
    return Array.from(m.entries())
      .sort((a, b) => b[1] - a[1])
      .slice(0, 12);
  }, [books]);

  const visible = useMemo(() => {
    let list = books.slice();
    if (onlyFavs) list = list.filter((b) => b.favorited);
    if (activeCategory) list = list.filter((b) => b.category === activeCategory);
    if (activeTag)
      list = list.filter((b) => (b.tags ?? []).some((t) => t === activeTag));

    if (trimmedQuery) {
      // Search mode: filter by score, then sort by relevance.
      list = list
        .map((b) => ({ b, s: scoreBook(b, trimmedQuery) }))
        .filter((x) => x.s > 0)
        .sort((a, b) => b.s - a.s)
        .map((x) => x.b);
    } else {
      list.sort((a, b) => {
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
    }
    return list;
  }, [books, onlyFavs, activeCategory, activeTag, trimmedQuery, sortBy]);

  const filtersActive =
    onlyFavs || activeCategory !== null || activeTag !== null || !!trimmedQuery;
  function clearAllFilters() {
    setOnlyFavs(false);
    setActiveCategory(null);
    setActiveTag(null);
    setQuery("");
  }

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
        <button
          type="button"
          className="stats-badge"
          onClick={() => setStatsOpen(true)}
          onContextMenu={(e) => {
            e.preventDefault();
            changeGoal();
          }}
          title="읽기 통계 보기 (우클릭: 목표 변경)"
        >
          {(() => {
            const today = stats?.today_seconds ?? 0;
            const todayMin = Math.round(today / 60);
            const pct = Math.min(1, todayMin / dailyGoal);
            const R = 11;
            const C = 2 * Math.PI * R;
            return (
              <svg className="stats-ring" width="28" height="28" viewBox="0 0 28 28">
                <circle cx="14" cy="14" r={R} className="stats-ring-bg" />
                <circle
                  cx="14"
                  cy="14"
                  r={R}
                  className="stats-ring-fill"
                  style={{
                    strokeDasharray: `${C}`,
                    strokeDashoffset: `${(1 - pct) * C}`,
                  }}
                />
              </svg>
            );
          })()}
          <span className="stats-streak">
            🔥 <strong>{stats?.streak_days ?? 0}</strong>
          </span>
          <span className="stats-divider">·</span>
          <span className="stats-today">
            <strong>{Math.round((stats?.today_seconds ?? 0) / 60)}</strong>
            <span className="stats-goal">/{dailyGoal}분</span>
          </span>
          <span className="stats-divider">·</span>
          <span className="stats-today" style={{ fontWeight: 600 }}>통계</span>
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
              <div className="library-search">
                <svg
                  className="library-search-icon"
                  width="16"
                  height="16"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                >
                  <circle cx="11" cy="11" r="7" />
                  <line x1="20" y1="20" x2="16.65" y2="16.65" />
                </svg>
                <input
                  type="search"
                  className="library-search-input"
                  placeholder="제목·작가·시리즈·태그·출판사·ISBN 검색…"
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                  aria-label="책 검색"
                />
                {query && (
                  <button
                    type="button"
                    className="library-search-clear"
                    onClick={() => setQuery("")}
                    aria-label="검색어 지우기"
                  >
                    ×
                  </button>
                )}
              </div>

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
                disabled={!!trimmedQuery}
                title={trimmedQuery ? "검색 중에는 관련도 순으로 정렬" : ""}
              >
                <option value="recent">최신순</option>
                <option value="rating">별점순</option>
                <option value="progress">읽은 정도</option>
                <option value="title">제목 순</option>
              </select>
            </div>

            {categoryCounts.length > 0 && (
              <div className="library-categories" role="tablist">
                <button
                  type="button"
                  className={`cat-chip${activeCategory === null ? " cat-chip-active" : ""}`}
                  onClick={() => setActiveCategory(null)}
                >
                  전체
                  <span className="cat-chip-count">{books.length}</span>
                </button>
                {categoryCounts.map(([cat, n]) => (
                  <button
                    key={cat}
                    type="button"
                    className={`cat-chip${activeCategory === cat ? " cat-chip-active" : ""}`}
                    onClick={() =>
                      setActiveCategory(activeCategory === cat ? null : cat)
                    }
                  >
                    {cat}
                    <span className="cat-chip-count">{n}</span>
                  </button>
                ))}
              </div>
            )}

            {topTags.length > 0 && (
              <div className="library-tagrow">
                <span className="library-tagrow-label">#</span>
                {topTags.map(([tag, n]) => (
                  <button
                    key={tag}
                    type="button"
                    className={`tag-chip${activeTag === tag ? " tag-chip-active" : ""}`}
                    onClick={() => setActiveTag(activeTag === tag ? null : tag)}
                  >
                    {tag}
                    <span className="tag-chip-count">{n}</span>
                  </button>
                ))}
              </div>
            )}

            {(filtersActive || trimmedQuery) && (
              <div className="library-resultline">
                <span className="library-resultcount">
                  {visible.length === 0
                    ? "검색 결과 없음"
                    : `${visible.length}권`}
                  {trimmedQuery && (
                    <span className="library-resultq"> · "{trimmedQuery}"</span>
                  )}
                  {activeCategory && (
                    <span className="library-resultq"> · {activeCategory}</span>
                  )}
                  {activeTag && (
                    <span className="library-resultq"> · #{activeTag}</span>
                  )}
                  {onlyFavs && (
                    <span className="library-resultq"> · ♥</span>
                  )}
                </span>
                <button
                  type="button"
                  className="library-clearall"
                  onClick={clearAllFilters}
                >
                  필터 초기화
                </button>
              </div>
            )}
            <div className="book-grid">
              {visible.map((book) => {
              const status = book.audio_status ?? "pending";
              const cooking = status === "pending" || status === "generating";
              const mine = !!(user && book.uploader_email && user.email === book.uploader_email);
              return (
                <div
                  key={book.id}
                  role="button"
                  tabIndex={0}
                  className={`book-card book-card-${status}${mine ? " book-card-mine" : ""}`}
                  onClick={() => onSelectBook(book)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter" || e.key === " ") {
                      e.preventDefault();
                      onSelectBook(book);
                    }
                  }}
                  title={book.title}
                >
                  <div className="book-cover">
                    <img
                      src={`/books/${book.id}/thumb${authQuery(user?.idToken)}`}
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
                    <div className="book-title">
                      {highlightMatch(book.title, trimmedQuery)}
                    </div>
                    {(book.author || book.series_name) && (
                      <div className="book-byline">
                        {book.author && (
                          <span className="book-author">
                            {highlightMatch(book.author, trimmedQuery)}
                          </span>
                        )}
                        {book.series_name && (
                          <span className="book-series">
                            · {highlightMatch(book.series_name, trimmedQuery)}
                            {book.series_index ? ` #${book.series_index}` : ""}
                          </span>
                        )}
                      </div>
                    )}
                    <div className="book-meta">
                      {book.category && (
                        <span className="book-cat-tag">{book.category}</span>
                      )}
                      {book.page_count}쪽 · {formatSize(book.size_bytes)} ·{" "}
                      {formatDate(book.uploaded_at)}
                    </div>
                    {(book.tags?.length ?? 0) > 0 && (
                      <div className="book-tagstrip">
                        {book.tags!.slice(0, 3).map((t) => (
                          <span key={t} className="book-tagstrip-tag">#{t}</span>
                        ))}
                      </div>
                    )}
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
                </div>
              );
            })}
            </div>
          </>
        )}
      </div>

      <StatsDashboard
        user={user}
        open={statsOpen}
        onClose={() => setStatsOpen(false)}
        onSelectBook={(id) => {
          const b = books.find((x) => x.id === id);
          if (b) {
            setStatsOpen(false);
            onSelectBook(b);
          }
        }}
      />
    </section>
  );
}
