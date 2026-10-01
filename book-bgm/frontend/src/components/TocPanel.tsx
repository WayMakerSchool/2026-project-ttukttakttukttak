import { useEffect, useState } from "react";

interface TocEntry {
  level: number;
  title: string;
  page: number;
}

interface Props {
  bookId: string;
  open: boolean;
  currentPage: number;
  onClose: () => void;
  onJump: (page: number) => void;
}

export function TocPanel({ bookId, open, currentPage, onClose, onJump }: Props) {
  const [toc, setToc] = useState<TocEntry[]>([]);
  const [summaries, setSummaries] = useState<Record<number, string>>({});
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!open || !bookId) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    Promise.all([
      fetch(`/books/${bookId}/toc`).then((r) => {
        if (!r.ok) throw new Error(`toc ${r.status}`);
        return r.json();
      }),
      fetch(`/books/${bookId}/summaries`).then((r) => (r.ok ? r.json() : { summaries: {} })),
    ])
      .then(([tocData, sumData]) => {
        if (cancelled) return;
        setToc((tocData.toc ?? []) as TocEntry[]);
        // Backend returns int-keyed dict in JSON as string keys; normalize.
        const raw = (sumData.summaries ?? {}) as Record<string, string>;
        const normalized: Record<number, string> = {};
        for (const [k, v] of Object.entries(raw)) {
          const n = parseInt(k, 10);
          if (!isNaN(n) && v) normalized[n] = v;
        }
        setSummaries(normalized);
        setLoading(false);
      })
      .catch((e) => {
        if (cancelled) return;
        setError(e instanceof Error ? e.message : String(e));
        setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [bookId, open]);

  // Close on Escape.
  useEffect(() => {
    if (!open) return;
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  if (!open) return null;

  // Highlight the entry whose page-range currently contains the active page.
  const activeIdx = (() => {
    let best = -1;
    for (let i = 0; i < toc.length; i++) {
      if (toc[i].page <= currentPage) best = i;
      else break;
    }
    return best;
  })();

  return (
    <>
      <div className="toc-backdrop" onClick={onClose} aria-hidden="true" />
      <aside className="toc-drawer" role="dialog" aria-label="목차">
        <header className="toc-header">
          <h2>목차</h2>
          <button
            className="toc-close"
            onClick={onClose}
            aria-label="닫기"
            type="button"
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <line x1="18" y1="6" x2="6" y2="18" />
              <line x1="6" y1="6" x2="18" y2="18" />
            </svg>
          </button>
        </header>

        <div className="toc-body">
          {loading && (
            <div className="toc-state">
              <div className="spinner" />
              <span>목차 분석 중…</span>
            </div>
          )}
          {!loading && error && (
            <div className="toc-state">
              <span className="error">목차를 불러오지 못했어요 ({error})</span>
            </div>
          )}
          {!loading && !error && toc.length === 0 && (
            <div className="toc-state">
              이 책에서는 뚜렷한 목차를 찾지 못했어요.
            </div>
          )}
          {!loading && toc.length > 0 && (
            <ul className="toc-list">
              {toc.map((entry, i) => (
                <li key={`${entry.page}-${i}`}>
                  <button
                    className={`toc-item toc-level-${Math.min(3, Math.max(1, entry.level))}${
                      i === activeIdx ? " toc-item-active" : ""
                    }`}
                    onClick={() => {
                      onJump(entry.page);
                      onClose();
                    }}
                    type="button"
                  >
                    <div className="toc-item-row">
                      <span className="toc-item-title">{entry.title}</span>
                      <span className="toc-item-page">{entry.page}</span>
                    </div>
                    {summaries[entry.page] && (
                      <span className="toc-item-summary">{summaries[entry.page]}</span>
                    )}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      </aside>
    </>
  );
}
