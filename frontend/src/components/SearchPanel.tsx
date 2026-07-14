import { useEffect, useMemo, useRef, useState } from "react";

interface Hit {
  page: number;
  before: string;
  match: string;
  after: string;
}

interface Props {
  bookId: string;
  open: boolean;
  onClose: () => void;
  onJump: (page: number) => void;
}

const MAX_RESULTS = 100;
const SNIPPET_BEFORE = 30;
const SNIPPET_AFTER = 60;

export function SearchPanel({ bookId, open, onClose, onJump }: Props) {
  const [pages, setPages] = useState<string[] | null>(null);
  const [loadingText, setLoadingText] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!open || !bookId || pages !== null) return;
    let cancelled = false;
    setLoadingText(true);
    setError(null);
    fetch(`/books/${bookId}/text`)
      .then((r) => (r.ok ? r.json() : Promise.reject(`status ${r.status}`)))
      .then((d) => {
        if (cancelled) return;
        setPages((d.pages ?? []) as string[]);
        setLoadingText(false);
      })
      .catch((e) => {
        if (cancelled) return;
        setError(String(e));
        setLoadingText(false);
      });
    return () => {
      cancelled = true;
    };
  }, [open, bookId, pages]);

  // Reset when book changes.
  useEffect(() => {
    setPages(null);
    setQuery("");
  }, [bookId]);

  useEffect(() => {
    if (!open) return;
    inputRef.current?.focus();
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  const hits = useMemo<Hit[]>(() => {
    const q = query.trim();
    if (!pages || q.length < 1) return [];
    const lowered = q.toLowerCase();
    const out: Hit[] = [];
    for (let i = 0; i < pages.length; i++) {
      const raw = pages[i] || "";
      const flat = raw.replace(/\s+/g, " ");
      let from = 0;
      while (out.length < MAX_RESULTS) {
        const idx = flat.toLowerCase().indexOf(lowered, from);
        if (idx < 0) break;
        const start = Math.max(0, idx - SNIPPET_BEFORE);
        const end = Math.min(flat.length, idx + q.length + SNIPPET_AFTER);
        out.push({
          page: i + 1,
          before: (start > 0 ? "…" : "") + flat.slice(start, idx),
          match: flat.slice(idx, idx + q.length),
          after: flat.slice(idx + q.length, end) + (end < flat.length ? "…" : ""),
        });
        from = idx + q.length;
        if (out.length >= MAX_RESULTS) break;
      }
      if (out.length >= MAX_RESULTS) break;
    }
    return out;
  }, [query, pages]);

  if (!open) return null;

  return (
    <>
      <div className="toc-backdrop" onClick={onClose} aria-hidden="true" />
      <aside className="toc-drawer" role="dialog" aria-label="책 내 검색">
        <header className="toc-header">
          <h2>검색</h2>
          <button className="toc-close" onClick={onClose} aria-label="닫기" type="button">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <line x1="18" y1="6" x2="6" y2="18" />
              <line x1="6" y1="6" x2="18" y2="18" />
            </svg>
          </button>
        </header>

        <div className="search-input-row">
          <input
            ref={inputRef}
            className="search-input"
            type="search"
            placeholder="검색어 입력…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
          {query && (
            <button
              className="search-clear"
              onClick={() => setQuery("")}
              aria-label="지우기"
              type="button"
            >
              ✕
            </button>
          )}
        </div>

        <div className="toc-body">
          {loadingText && (
            <div className="toc-state">
              <div className="spinner" />
              <span>본문 로딩 중…</span>
            </div>
          )}
          {error && !loadingText && (
            <div className="toc-state">
              <span className="error">{error}</span>
            </div>
          )}
          {!loadingText && !error && !query.trim() && (
            <div className="toc-state">
              검색어를 입력하면 본문에서 찾아드려요.
            </div>
          )}
          {!loadingText && !error && query.trim() && hits.length === 0 && (
            <div className="toc-state">
              일치하는 결과가 없어요.
            </div>
          )}
          {hits.length > 0 && (
            <>
              <div className="search-count">{hits.length}{hits.length >= MAX_RESULTS ? "+" : ""}건</div>
              <ul className="search-list">
                {hits.map((h, i) => (
                  <li key={i}>
                    <button
                      className="search-item"
                      type="button"
                      onClick={() => {
                        onJump(h.page);
                        onClose();
                      }}
                    >
                      <div className="search-item-page">p.{h.page}</div>
                      <div className="search-item-snippet">
                        <span>{h.before}</span>
                        <mark>{h.match}</mark>
                        <span>{h.after}</span>
                      </div>
                    </button>
                  </li>
                ))}
              </ul>
            </>
          )}
        </div>
      </aside>
    </>
  );
}
