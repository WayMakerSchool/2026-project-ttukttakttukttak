import { useEffect, useRef, useState } from "react";
import { Document, Page, pdfjs } from "react-pdf";
import "react-pdf/dist/Page/AnnotationLayer.css";
import "react-pdf/dist/Page/TextLayer.css";
// Bundled worker — CDN workers are blocked by the backend CSP (script-src 'self').
import pdfWorkerUrl from "pdfjs-dist/build/pdf.worker.min.mjs?url";

pdfjs.GlobalWorkerOptions.workerSrc = pdfWorkerUrl;

interface Props {
  fileUrl: string;
  page: number;
  pageCount: number;
  onPageChange: (page: number) => void;
  onLoadError?: (err: Error) => void;
  /** "page" = one flat PDF page at a time, "scroll" = continuous vertical scroll */
  mode: "page" | "scroll";
  zoom?: number;
  sfxVolume?: number;
}

const MAX_WIDTH = 760;
const MIN_WIDTH = 280;

function getPageWidth(): number {
  if (typeof window === "undefined") return MAX_WIDTH;
  return Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, window.innerWidth - 48));
}

export function FlatPdfViewer({
  fileUrl,
  page,
  pageCount,
  onPageChange,
  onLoadError,
  mode,
  zoom = 1,
  sfxVolume = 0.5,
}: Props) {
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const prevPageRef = useRef(page);
  const containerRef = useRef<HTMLDivElement>(null);
  const [baseWidth, setBaseWidth] = useState(getPageWidth);
  const pageWidth = Math.round(baseWidth * zoom);

  useEffect(() => {
    function update() {
      setBaseWidth(getPageWidth());
    }
    window.addEventListener("resize", update);
    window.addEventListener("orientationchange", update);
    return () => {
      window.removeEventListener("resize", update);
      window.removeEventListener("orientationchange", update);
    };
  }, []);

  useEffect(() => {
    const a = new Audio("/page-flip.mp3");
    a.preload = "auto";
    a.volume = sfxVolume;
    audioRef.current = a;
    return () => {
      audioRef.current = null;
    };
  }, []);

  useEffect(() => {
    if (audioRef.current) audioRef.current.volume = sfxVolume;
  }, [sfxVolume]);

  function playFlip() {
    const a = audioRef.current;
    if (!a) return;
    try {
      a.currentTime = 0;
      const p = a.play();
      if (p && typeof p.catch === "function") p.catch(() => {});
    } catch {
      /* autoplay block */
    }
  }

  // Forward (next page) plays sound; backward (previous page) is silent.
  useEffect(() => {
    if (page === prevPageRef.current) return;
    const forward = page > prevPageRef.current;
    prevPageRef.current = page;
    if (mode === "page") {
      if (forward) playFlip();
    } else if (mode === "scroll") {
      const el = containerRef.current?.querySelector<HTMLElement>(`[data-page="${page}"]`);
      if (el) {
        el.scrollIntoView({ behavior: "smooth", block: "start" });
      }
    }
  }, [page, mode]);

  // Arrow keys only meaningful in page mode (scroll uses natural scroll).
  useEffect(() => {
    if (mode !== "page") return;
    function onKey(e: KeyboardEvent) {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return;
      if (e.key === "ArrowLeft") onPageChange(Math.max(1, page - 1));
      else if (e.key === "ArrowRight") onPageChange(Math.min(pageCount, page + 1));
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [page, pageCount, onPageChange, mode]);

  // Scroll mode: track which page is currently in view and report it up.
  useEffect(() => {
    if (mode !== "scroll") return;
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
      { threshold: [0.3, 0.55, 0.8] }
    );
    container.querySelectorAll("[data-page]").forEach((el) => observer.observe(el));
    return () => observer.disconnect();
  }, [mode, onPageChange, pageCount]);

  if (mode === "page") {
    return (
      <div className="pdf-frame flat-frame">
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
    );
  }

  return (
    <div className="pdf-frame scroll-frame" ref={containerRef}>
      <Document
        file={fileUrl}
        loading={<div className="pdf-loading">PDF 로딩 중…</div>}
        onLoadError={onLoadError}
      >
        {Array.from({ length: pageCount }, (_, i) => (
          <div key={i + 1} className="scroll-page" data-page={i + 1}>
            <Page
              pageNumber={i + 1}
              width={pageWidth}
              renderAnnotationLayer={false}
              renderTextLayer={false}
            />
          </div>
        ))}
      </Document>
    </div>
  );
}
