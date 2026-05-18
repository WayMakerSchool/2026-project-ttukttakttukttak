import { useEffect, useRef, useState } from "react";
import { Document, Page, pdfjs } from "react-pdf";
import "react-pdf/dist/Page/AnnotationLayer.css";
import "react-pdf/dist/Page/TextLayer.css";

pdfjs.GlobalWorkerOptions.workerSrc = `https://unpkg.com/pdfjs-dist@${pdfjs.version}/build/pdf.worker.min.mjs`;

const MAX_PAGE_WIDTH = 720;
const MIN_PAGE_WIDTH = 280;
// Pixels of horizontal movement to count as a swipe; below this we treat it
// as a tap so users can still tap on the page without accidentally flipping.
const SWIPE_THRESHOLD = 55;
const SWIPE_MAX_VERTICAL = 50;
const SWIPE_MAX_DURATION_MS = 600;

interface Props {
  fileUrl: string;
  page: number;
  pageCount: number;
  onPageChange: (page: number) => void;
  onLoadError?: (err: Error) => void;
}

export function PdfViewer({ fileUrl, page, pageCount, onPageChange, onLoadError }: Props) {
  const wrapRef = useRef<HTMLDivElement>(null);
  const prevPageRef = useRef(page);
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const swipeStart = useRef<{ x: number; y: number; t: number; id: number } | null>(null);

  const [pageWidth, setPageWidth] = useState<number>(() => {
    if (typeof window === "undefined") return MAX_PAGE_WIDTH;
    return Math.min(MAX_PAGE_WIDTH, Math.max(MIN_PAGE_WIDTH, window.innerWidth - 32));
  });

  // Responsive page width — react-pdf re-rasterizes when width changes.
  useEffect(() => {
    function update() {
      const w = Math.min(MAX_PAGE_WIDTH, Math.max(MIN_PAGE_WIDTH, window.innerWidth - 32));
      setPageWidth(w);
    }
    update();
    window.addEventListener("resize", update);
    window.addEventListener("orientationchange", update);
    return () => {
      window.removeEventListener("resize", update);
      window.removeEventListener("orientationchange", update);
    };
  }, []);

  // Keyboard navigation.
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return;
      if (e.key === "ArrowLeft") onPageChange(Math.max(1, page - 1));
      else if (e.key === "ArrowRight") onPageChange(Math.min(pageCount, page + 1));
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [page, pageCount, onPageChange]);

  // Audio init.
  useEffect(() => {
    const a = new Audio("/page-flip.mp3");
    a.preload = "auto";
    a.volume = 0.55;
    audioRef.current = a;
    return () => {
      audioRef.current = null;
    };
  }, []);

  // On page change: play flip sound + 3D flip animation.
  useEffect(() => {
    if (page === prevPageRef.current) return;
    const forward = page > prevPageRef.current;
    prevPageRef.current = page;

    const a = audioRef.current;
    if (a) {
      try {
        a.currentTime = 0;
        const p = a.play();
        if (p && typeof p.catch === "function") p.catch(() => {});
      } catch {
        /* ignore autoplay block */
      }
    }

    const el = wrapRef.current;
    if (el && typeof el.animate === "function") {
      const dir = forward ? -1 : 1;
      el.animate(
        [
          { transform: "perspective(1500px) rotateY(0deg)", opacity: 1, offset: 0 },
          {
            transform: `perspective(1500px) rotateY(${dir * 55}deg) translateX(${dir * -24}px)`,
            opacity: 0.15,
            offset: 0.45,
          },
          {
            transform: `perspective(1500px) rotateY(${dir * -25}deg) translateX(${dir * 14}px)`,
            opacity: 0.45,
            offset: 0.6,
          },
          { transform: "perspective(1500px) rotateY(0deg)", opacity: 1, offset: 1 },
        ],
        { duration: 560, easing: "cubic-bezier(0.22, 1, 0.36, 1)" }
      );
    }
  }, [page]);

  // Swipe gestures — pointerdown/up so a single handler works for both
  // mouse-drag (desktop) and touch (iPad / phone).
  function onPointerDown(e: React.PointerEvent<HTMLDivElement>) {
    swipeStart.current = {
      x: e.clientX,
      y: e.clientY,
      t: Date.now(),
      id: e.pointerId,
    };
  }

  function onPointerUp(e: React.PointerEvent<HTMLDivElement>) {
    const start = swipeStart.current;
    swipeStart.current = null;
    if (!start || start.id !== e.pointerId) return;
    const dx = e.clientX - start.x;
    const dy = e.clientY - start.y;
    const dt = Date.now() - start.t;
    if (
      dt > SWIPE_MAX_DURATION_MS ||
      Math.abs(dy) > SWIPE_MAX_VERTICAL ||
      Math.abs(dx) < SWIPE_THRESHOLD
    ) {
      return;
    }
    if (dx < 0) {
      onPageChange(Math.min(pageCount, page + 1));
    } else {
      onPageChange(Math.max(1, page - 1));
    }
  }

  return (
    <div
      className="pdf-frame"
      onPointerDown={onPointerDown}
      onPointerUp={onPointerUp}
      onPointerCancel={() => (swipeStart.current = null)}
    >
      <div ref={wrapRef} className="pdf-flip-wrap">
        <Document
          file={fileUrl}
          loading={<div className="pdf-loading">PDF 로딩 중…</div>}
          onLoadError={onLoadError}
        >
          <Page
            pageNumber={page}
            width={pageWidth}
            renderAnnotationLayer={false}
            renderTextLayer={false}
          />
        </Document>
      </div>
    </div>
  );
}
