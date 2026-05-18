import { useEffect, useRef, useState } from "react";
import { Document, Page, pdfjs } from "react-pdf";
import HTMLFlipBookRaw from "react-pageflip";
import "react-pdf/dist/Page/AnnotationLayer.css";
import "react-pdf/dist/Page/TextLayer.css";

pdfjs.GlobalWorkerOptions.workerSrc = `https://unpkg.com/pdfjs-dist@${pdfjs.version}/build/pdf.worker.min.mjs`;

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const HTMLFlipBook = HTMLFlipBookRaw as unknown as any;

type FlipMode = "spread" | "portrait";

// Two-page spread kicks in once the viewport is wide enough for both pages to
// be readable. Below this we fall back to single-page (book opens like a phone).
const SPREAD_MIN_VIEWPORT = 820;

// Per-page sizes, per mode.
const PORTRAIT_MAX = 540;
const PORTRAIT_MIN = 280;
const SPREAD_MAX_PER_PAGE = 440;
const SPREAD_MIN_PER_PAGE = 300;

const PAGE_ASPECT = 1.4;

interface Props {
  fileUrl: string;
  page: number;
  pageCount: number;
  onPageChange: (page: number) => void;
  onLoadError?: (err: Error) => void;
}

function computeLayout(): { mode: FlipMode; pageWidth: number } {
  if (typeof window === "undefined") {
    return { mode: "spread", pageWidth: PORTRAIT_MAX };
  }
  const vw = window.innerWidth;
  if (vw >= SPREAD_MIN_VIEWPORT) {
    const perPage = Math.min(
      SPREAD_MAX_PER_PAGE,
      Math.max(SPREAD_MIN_PER_PAGE, Math.floor((vw - 80) / 2))
    );
    return { mode: "spread", pageWidth: perPage };
  }
  const single = Math.min(PORTRAIT_MAX, Math.max(PORTRAIT_MIN, vw - 48));
  return { mode: "portrait", pageWidth: single };
}

export function PdfViewer({ fileUrl, page, pageCount, onPageChange, onLoadError }: Props) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const flipBookRef = useRef<any>(null);
  const prevPageRef = useRef(page);
  const audioRef = useRef<HTMLAudioElement | null>(null);

  const [layout, setLayout] = useState(computeLayout);
  const { mode, pageWidth } = layout;
  const pageHeight = Math.round(pageWidth * PAGE_ASPECT);

  // Responsive layout.
  useEffect(() => {
    function update() {
      setLayout(computeLayout());
    }
    window.addEventListener("resize", update);
    window.addEventListener("orientationchange", update);
    return () => {
      window.removeEventListener("resize", update);
      window.removeEventListener("orientationchange", update);
    };
  }, []);

  // Page-flip sound.
  useEffect(() => {
    const a = new Audio("/page-flip.mp3");
    a.preload = "auto";
    a.volume = 0.55;
    audioRef.current = a;
    return () => {
      audioRef.current = null;
    };
  }, []);

  function playFlipSound() {
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

  // Keyboard navigation goes through the flipbook so the user sees the
  // same 3D flip animation as a finger drag.
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return;
      const inst = flipBookRef.current?.pageFlip?.();
      if (!inst) return;
      if (e.key === "ArrowLeft") inst.flipPrev();
      else if (e.key === "ArrowRight") inst.flipNext();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  // Mirror external page changes (dock buttons, TOC jump, page input) into
  // the flipbook. Defer to next frame so concurrent unmounts (e.g. TOC drawer
  // closing) finish first; otherwise the flip() call can no-op silently.
  useEffect(() => {
    if (page === prevPageRef.current) return;
    prevPageRef.current = page;
    const targetIdx =
      mode === "spread" ? Math.floor((page - 1) / 2) * 2 : page - 1;
    const handle = requestAnimationFrame(() => {
      const inst = flipBookRef.current?.pageFlip?.();
      if (inst && typeof inst.flip === "function") {
        try {
          inst.flip(targetIdx);
        } catch (err) {
          console.warn("[PdfViewer] flip failed", err);
        }
      }
    });
    return () => cancelAnimationFrame(handle);
  }, [page, mode]);

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  function handleFlip(e: any) {
    const next = (e.data as number) + 1;
    if (next === prevPageRef.current) return;
    prevPageRef.current = next;
    onPageChange(next);
  }

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  function handleChangeState(e: any) {
    if (e.data === "flipping") playFlipSound();
  }

  return (
    <div className={`pdf-frame book-frame book-frame-${mode}`}>
      <Document
        file={fileUrl}
        loading={<div className="pdf-loading">PDF 로딩 중…</div>}
        onLoadError={onLoadError}
      >
        <HTMLFlipBook
          // Forcing a remount on mode switch lets the library re-measure for
          // single vs spread without weird half-states.
          key={mode}
          ref={flipBookRef}
          width={pageWidth}
          height={pageHeight}
          size="fixed"
          minWidth={Math.min(PORTRAIT_MIN, SPREAD_MIN_PER_PAGE)}
          maxWidth={Math.max(PORTRAIT_MAX, SPREAD_MAX_PER_PAGE)}
          minHeight={Math.round(SPREAD_MIN_PER_PAGE * PAGE_ASPECT)}
          maxHeight={Math.round(PORTRAIT_MAX * PAGE_ASPECT)}
          drawShadow
          maxShadowOpacity={0.55}
          showCover={false}
          mobileScrollSupport={false}
          usePortrait={mode === "portrait"}
          flippingTime={700}
          startPage={Math.max(0, page - 1)}
          className="flipbook"
          style={{}}
          onFlip={handleFlip}
          onChangeState={handleChangeState}
        >
          {Array.from({ length: pageCount }, (_, i) => (
            <div key={i + 1} className="flip-page">
              <Page
                pageNumber={i + 1}
                width={pageWidth}
                renderAnnotationLayer={false}
                renderTextLayer={false}
              />
            </div>
          ))}
        </HTMLFlipBook>
      </Document>
    </div>
  );
}
