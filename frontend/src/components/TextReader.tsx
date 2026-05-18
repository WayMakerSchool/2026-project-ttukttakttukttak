import { useEffect, useRef, useState } from "react";

interface Props {
  bookId: string;
  page: number;
  pageCount: number;
  onPageChange: (page: number) => void;
  /** "novel" = serif comfortable, "comfort" = larger sans + sepia */
  mode: "novel" | "comfort";
}

export function TextReader({ bookId, page, pageCount, onPageChange, mode }: Props) {
  const [pages, setPages] = useState<string[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const prevPageRef = useRef(page);

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
    <div className={`text-reader text-reader-${mode}`} ref={containerRef}>
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
  );
}
