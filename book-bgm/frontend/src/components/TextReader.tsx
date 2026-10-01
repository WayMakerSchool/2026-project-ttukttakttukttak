import { useEffect, useRef, useState } from "react";
import { authHeaders, type AuthUser } from "../lib/auth";
import { progressHeaders } from "../lib/client-id";

interface Props {
  bookId: string;
  page: number;
  pageCount: number;
  onPageChange: (page: number) => void;
  /** "novel" = serif comfortable, "comfort" = larger sans + sepia */
  mode: "novel" | "comfort";
  zoom?: number;
  user?: AuthUser | null;
  onHighlighted?: () => void;
}

interface SelectionState {
  text: string;
  page: number;
  x: number;
  y: number;
}

interface DictEntry {
  word: string;
  pos?: string;
  definition?: string;
  hanja?: string;
  pronunciation?: string;
  translations?: { en?: string; ja?: string };
  example?: string;
  note?: string;
}

export function TextReader({
  bookId,
  page,
  pageCount,
  onPageChange,
  mode,
  zoom = 1,
  user = null,
  onHighlighted,
}: Props) {
  const [pages, setPages] = useState<string[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const prevPageRef = useRef(page);
  const [selection, setSelection] = useState<SelectionState | null>(null);
  const [saving, setSaving] = useState(false);
  const [lookupResult, setLookupResult] = useState<DictEntry | null>(null);
  const [lookupError, setLookupError] = useState<string | null>(null);
  const [lookingUp, setLookingUp] = useState(false);

  async function lookupSelection() {
    if (!selection) return;
    setLookingUp(true);
    setLookupResult(null);
    setLookupError(null);
    // Find ±1 sentence around the selection for context disambiguation.
    const ctx = pages?.[selection.page - 1]?.slice(0, 400) || "";
    try {
      const res = await fetch("/dict/lookup", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ word: selection.text, context: ctx }),
      });
      if (!res.ok) throw new Error(`status ${res.status}`);
      const data = (await res.json()) as DictEntry;
      setLookupResult(data);
    } catch (e) {
      setLookupError(e instanceof Error ? e.message : String(e));
    } finally {
      setLookingUp(false);
    }
  }

  // Hide lookup when user clicks outside / selection changes.
  useEffect(() => {
    if (!selection) {
      setLookupResult(null);
      setLookupError(null);
      setLookingUp(false);
    }
  }, [selection]);

  // Watch for text selection inside this reader and surface a "highlight" button.
  useEffect(() => {
    function updateSelection() {
      const sel = window.getSelection();
      if (!sel || sel.isCollapsed || sel.rangeCount === 0) {
        setSelection(null);
        return;
      }
      const text = sel.toString().trim();
      if (text.length < 2) {
        setSelection(null);
        return;
      }
      const range = sel.getRangeAt(0);
      const container = containerRef.current;
      if (!container || !container.contains(range.startContainer)) {
        setSelection(null);
        return;
      }
      // Find which page section the selection lives in.
      let node: Node | null = range.startContainer;
      let pageNum = page;
      while (node && node !== container) {
        if (node instanceof HTMLElement && node.dataset.page) {
          pageNum = parseInt(node.dataset.page, 10) || page;
          break;
        }
        node = node.parentNode;
      }
      const rect = range.getBoundingClientRect();
      setSelection({
        text: text.slice(0, 2000),
        page: pageNum,
        x: rect.left + rect.width / 2,
        y: rect.top,
      });
    }
    document.addEventListener("selectionchange", updateSelection);
    return () => document.removeEventListener("selectionchange", updateSelection);
  }, [page]);

  async function saveHighlight() {
    if (!selection) return;
    setSaving(true);
    try {
      const res = await fetch(`/books/${bookId}/highlights`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          ...progressHeaders(),
          ...authHeaders(user),
        },
        body: JSON.stringify({
          page: selection.page,
          text: selection.text,
          note: null,
          color: null,
        }),
      });
      if (res.ok || res.status === 201) {
        onHighlighted?.();
        window.getSelection()?.removeAllRanges();
        setSelection(null);
      }
    } catch {
      /* ignore */
    } finally {
      setSaving(false);
    }
  }

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    fetch(`/books/${bookId}/text`)
      .then((r) => {
        if (!r.ok) throw new Error(`status ${r.status}`);
        return r.json();
      })
      .then((data) => {
        if (cancelled) return;
        setPages((data.pages ?? []) as string[]);
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
  }, [bookId]);

  // External page change → scroll to that page section.
  useEffect(() => {
    if (page === prevPageRef.current) return;
    prevPageRef.current = page;
    const el = containerRef.current?.querySelector<HTMLElement>(`[data-page="${page}"]`);
    if (el) {
      el.scrollIntoView({ behavior: "smooth", block: "start" });
    }
  }, [page]);

  // Detect which page section is currently visible and propagate up so the
  // music + dock counter stay in sync with scrolling.
  useEffect(() => {
    if (!pages) return;
    const container = containerRef.current;
    if (!container) return;
    const observer = new IntersectionObserver(
      (entries) => {
        let bestPage = -1;
        let bestRatio = 0;
        for (const entry of entries) {
          if (entry.isIntersecting && entry.intersectionRatio > bestRatio) {
            const p = parseInt(entry.target.getAttribute("data-page") || "0", 10);
            if (p > 0) {
              bestPage = p;
              bestRatio = entry.intersectionRatio;
            }
          }
        }
        if (bestPage > 0 && bestPage !== prevPageRef.current) {
          prevPageRef.current = bestPage;
          onPageChange(bestPage);
        }
      },
      { threshold: [0.2, 0.5, 0.8] }
    );
    container.querySelectorAll("[data-page]").forEach((el) => observer.observe(el));
    return () => observer.disconnect();
  }, [pages, onPageChange]);

  if (loading) {
    return (
      <div className="text-reader-state">
        <div className="spinner" />
        <span>본문 추출 중…</span>
      </div>
    );
  }

  if (error) {
    return (
      <div className="text-reader-state">
        <span className="error">본문을 불러오지 못했어요 ({error})</span>
      </div>
    );
  }

  if (!pages || pages.length === 0) {
    return (
      <div className="text-reader-state">
        <span>이 PDF에서는 본문 텍스트가 추출되지 않아요. 다른 보기 모드를 시도해주세요.</span>
      </div>
    );
  }

  return (
    <>
      {selection && !lookupResult && !lookingUp && (
        <div
          className="selection-actions"
          onMouseDown={(e) => e.preventDefault()}
          style={{
            position: "fixed",
            left: `${Math.max(100, Math.min(window.innerWidth - 100, selection.x))}px`,
            top: `${Math.max(60, selection.y - 48)}px`,
          }}
        >
          <button
            type="button"
            className="highlight-floating"
            onClick={saveHighlight}
            disabled={saving}
          >
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="m9 11-6 6v3h3l6-6" />
              <path d="m12 8 6-6 4 4-6 6" />
            </svg>
            <span>{saving ? "저장 중…" : "하이라이트"}</span>
          </button>
          <button
            type="button"
            className="lookup-floating"
            onClick={lookupSelection}
          >
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
              <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2Z" />
              <path d="M9 7h6M9 11h4" />
            </svg>
            <span>사전</span>
          </button>
        </div>
      )}

      {selection && (lookingUp || lookupResult || lookupError) && (
        <div
          className="lookup-popup"
          onMouseDown={(e) => e.preventDefault()}
          style={{
            position: "fixed",
            left: `${Math.max(220, Math.min(window.innerWidth - 220, selection.x))}px`,
            top: `${Math.max(60, selection.y + 20)}px`,
          }}
        >
          <button
            type="button"
            className="lookup-close"
            onClick={() => {
              setLookupResult(null);
              setLookupError(null);
              setLookingUp(false);
              window.getSelection()?.removeAllRanges();
              setSelection(null);
            }}
            aria-label="닫기"
          >
            ✕
          </button>
          <div className="lookup-headword">
            <span className="lookup-word">{lookupResult?.word || selection.text.slice(0, 40)}</span>
            {lookupResult?.hanja && <span className="lookup-hanja">{lookupResult.hanja}</span>}
            {lookupResult?.pos && <span className="lookup-pos">{lookupResult.pos}</span>}
          </div>
          {lookupResult?.pronunciation && (
            <div className="lookup-pron">[{lookupResult.pronunciation}]</div>
          )}
          {lookingUp && (
            <div className="lookup-loading">
              <span className="spinner-sm" /> 사전 조회 중…
            </div>
          )}
          {lookupError && (
            <div className="lookup-error">⚠ {lookupError}</div>
          )}
          {lookupResult?.definition && (
            <div className="lookup-def">{lookupResult.definition}</div>
          )}
          {lookupResult?.translations && (lookupResult.translations.en || lookupResult.translations.ja) && (
            <div className="lookup-translations">
              {lookupResult.translations.en && (
                <div className="lookup-trans-row">
                  <span className="lookup-trans-flag">EN</span>
                  <span>{lookupResult.translations.en}</span>
                </div>
              )}
              {lookupResult.translations.ja && (
                <div className="lookup-trans-row">
                  <span className="lookup-trans-flag">JA</span>
                  <span>{lookupResult.translations.ja}</span>
                </div>
              )}
            </div>
          )}
          {lookupResult?.example && (
            <div className="lookup-example">"{lookupResult.example}"</div>
          )}
          {lookupResult?.note && (
            <div className="lookup-note">{lookupResult.note}</div>
          )}
        </div>
      )}
    <div
      className={`text-reader text-reader-${mode}`}
      ref={containerRef}
      style={{ ["--reader-zoom" as string]: zoom }}
    >
      {pages.map((text, i) => {
        const trimmed = (text || "").trim();
        const paragraphs = trimmed.split(/\n\n+/);
        return (
          <section key={i + 1} className="text-reader-page" data-page={i + 1}>
            <div className="text-reader-pagenum">
              {i + 1} / {pageCount}
            </div>
            <div className="text-reader-body">
              {paragraphs.length === 0 || (paragraphs.length === 1 && !paragraphs[0]) ? (
                <p className="text-reader-empty">(빈 페이지)</p>
              ) : (
                paragraphs.map((para, j) => <p key={j}>{para}</p>)
              )}
            </div>
          </section>
        );
      })}
    </div>
    </>
  );
}
