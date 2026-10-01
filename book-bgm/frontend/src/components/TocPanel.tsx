import { useEffect, useState } from "react";
import { authHeaders, loadUser } from "../lib/auth";
import { authQuery, progressHeaders } from "../lib/client-id";

// Identity headers for every middleware-protected request. Reading
// `loadUser()` per-call keeps it cheap (it's just localStorage).
function idHeaders(): Record<string, string> {
  return { ...progressHeaders(), ...authHeaders(loadUser()) };
}

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

type ImgState = "idle" | "loading" | "ready" | "error";

export function TocPanel({ bookId, open, currentPage, onClose, onJump }: Props) {
  const [toc, setToc] = useState<TocEntry[]>([]);
  const [summaries, setSummaries] = useState<Record<number, string>>({});
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // page -> state (independent so several chapters can render at once)
  const [imgState, setImgState] = useState<Record<number, ImgState>>({});
  // page -> cache-busting token; bumped each time the image is (re)generated
  const [imgVer, setImgVer] = useState<Record<number, number>>({});
  const [imgErr, setImgErr] = useState<Record<number, string>>({});

  useEffect(() => {
    if (!open || !bookId) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    const h = idHeaders();
    Promise.all([
      fetch(`/books/${bookId}/toc`, { headers: h }).then((r) => {
        if (!r.ok) throw new Error(`toc ${r.status}`);
        return r.json();
      }),
      fetch(`/books/${bookId}/summaries`, { headers: h }).then((r) => (r.ok ? r.json() : { summaries: {} })),
      fetch(`/books/${bookId}/images`, { headers: h }).then((r) => (r.ok ? r.json() : { pages: [] })),
    ])
      .then(([tocData, sumData, imgData]) => {
        if (cancelled) return;
        setToc((tocData.toc ?? []) as TocEntry[]);
        const raw = (sumData.summaries ?? {}) as Record<string, string>;
        const normalized: Record<number, string> = {};
        for (const [k, v] of Object.entries(raw)) {
          const n = parseInt(k, 10);
          if (!isNaN(n) && v) normalized[n] = v;
        }
        setSummaries(normalized);
        const ready: Record<number, ImgState> = {};
        const vers: Record<number, number> = {};
        for (const p of (imgData.pages ?? []) as number[]) {
          ready[p] = "ready";
          vers[p] = 1;
        }
        setImgState(ready);
        setImgVer(vers);
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

  useEffect(() => {
    if (!open) return;
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  async function generateImage(page: number) {
    setImgState((s) => ({ ...s, [page]: "loading" }));
    setImgErr((e) => {
      const copy = { ...e };
      delete copy[page];
      return copy;
    });
    try {
      const r = await fetch(`/books/${bookId}/chapters/${page}/image`, {
        method: "POST",
        headers: idHeaders(),
      });
      if (!r.ok) {
        const detail = await r.text();
        throw new Error(detail || `HTTP ${r.status}`);
      }
      setImgState((s) => ({ ...s, [page]: "ready" }));
      setImgVer((v) => ({ ...v, [page]: (v[page] ?? 0) + 1 }));
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      setImgState((s) => ({ ...s, [page]: "error" }));
      setImgErr((er) => ({ ...er, [page]: msg }));
    }
  }

  if (!open) return null;

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
              {toc.map((entry, i) => {
                const state: ImgState = imgState[entry.page] ?? "idle";
                const ver = imgVer[entry.page] ?? 0;
                return (
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
                      {summaries[entry.page] && entry.page <= currentPage && (
                        <span className="toc-item-summary">{summaries[entry.page]}</span>
                      )}
                      {summaries[entry.page] && entry.page > currentPage && (
                        <span className="toc-item-summary toc-item-summary-locked">
                          🔒 아직 읽지 않은 챕터 — 스포일러 방지
                        </span>
                      )}
                    </button>
                    <div className="toc-image-row">
                      {state === "ready" && (
                        <img
                          className="toc-chapter-image"
                          src={`/books/${bookId}/chapters/${entry.page}/image${authQuery(loadUser()?.idToken)}&v=${ver}`}
                          alt={`${entry.title} 장면`}
                          loading="lazy"
                        />
                      )}
                      {state === "error" && (
                        <div className="toc-image-error">
                          이미지 생성 실패{imgErr[entry.page] ? ` · ${imgErr[entry.page]}` : ""}
                        </div>
                      )}
                      <button
                        type="button"
                        className="toc-image-btn"
                        disabled={state === "loading"}
                        onClick={(e) => {
                          e.stopPropagation();
                          generateImage(entry.page);
                        }}
                      >
                        {state === "loading" ? (
                          <>
                            <span className="spinner-sm" />
                            <span>그리는 중…</span>
                          </>
                        ) : state === "ready" ? (
                          "다시 그리기"
                        ) : (
                          "사진 만들기"
                        )}
                      </button>
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      </aside>
    </>
  );
}
