import { useEffect, useState, type CSSProperties } from "react";
import { PdfViewer } from "./components/PdfViewer";
import { AudioPlayer } from "./components/AudioPlayer";
import { Library, type BookSummary } from "./components/Library";
import { BookDetail, type BookDetailData } from "./components/BookDetail";
import { TocPanel } from "./components/TocPanel";
import { FlatPdfViewer } from "./components/FlatPdfViewer";
import { TextReader } from "./components/TextReader";
import { AuthButton } from "./components/AuthButton";
import { authHeaders, loadUser, type AuthUser } from "./lib/auth";
import { moodAccent, moodToCss } from "./lib/mood-colors";

type Mood = {
  instruments?: string[];
  genre?: string;
  bpm?: number;
  mood?: string;
  intensity?: number;
  prompt?: string;
};

type View = "landing" | "library" | "detail" | "reader";
type ViewMode = "book" | "page" | "scroll" | "novel" | "comfort";

const VIEW_MODES: { id: ViewMode; label: string; sub: string }[] = [
  { id: "book", label: "Book", sub: "3D 책장 넘김" },
  { id: "page", label: "Page", sub: "한 장씩 보기" },
  { id: "scroll", label: "Scroll", sub: "전체 스크롤" },
  { id: "novel", label: "Novel", sub: "웹소설 형식" },
  { id: "comfort", label: "Comfort", sub: "큰 글씨 세피아" },
];

const STEPS = [
  {
    n: "01",
    title: "PDF 업로드",
    desc: "읽고 싶은 PDF 책을 드래그하거나 클릭해서 올리세요. 본문 텍스트가 추출 가능한 책이면 됩니다.",
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
        <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
        <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2Z" />
      </svg>
    ),
  },
  {
    n: "02",
    title: "분위기 분석",
    desc: "Gemini가 페이지마다 분위기·악기·템포를 한 번에 정리합니다. 한 권 분량으로 수 초~수십 초 정도.",
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
        <path d="M12 3v3" />
        <path d="M12 18v3" />
        <path d="M3 12h3" />
        <path d="M18 12h3" />
        <path d="m5.6 5.6 2.1 2.1" />
        <path d="m16.3 16.3 2.1 2.1" />
        <path d="m5.6 18.4 2.1-2.1" />
        <path d="m16.3 7.7 2.1-2.1" />
        <circle cx="12" cy="12" r="3" />
      </svg>
    ),
  },
  {
    n: "03",
    title: "읽으면서 듣기",
    desc: "‘음악 시작’을 누르고 페이지를 넘기세요. 장면 분위기가 바뀌면 음악도 자연스럽게 따라 바뀝니다.",
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
        <path d="M9 18V5l12-2v13" />
        <circle cx="6" cy="18" r="3" />
        <circle cx="18" cy="16" r="3" />
      </svg>
    ),
  },
];

const TIPS = [
  "이어폰이나 헤드폰을 권장합니다. 미묘한 분위기 변화가 더 잘 느껴져요.",
  "스캔 이미지로만 된 PDF는 텍스트가 없어 분석되지 않아요. 본문이 텍스트인 PDF로 올려주세요.",
  "음악을 처음 시작할 때 1~2초 정도 버퍼링이 있을 수 있습니다.",
  "페이지를 빠르게 넘기면 음악 전환이 살짝 뒤따라옵니다 — 자연스러운 크로스페이드를 위해서예요.",
];

function scrollToId(id: string) {
  document.getElementById(id)?.scrollIntoView({ behavior: "smooth", block: "start" });
}

export default function App() {
  const [view, setView] = useState<View>("landing");
  const [bookId, setBookId] = useState<string | null>(null);
  const [detailBookId, setDetailBookId] = useState<string | null>(null);
  const [pageCount, setPageCount] = useState(0);
  const [page, setPage] = useState(1);
  const [moods, setMoods] = useState<Mood[]>([]);
  const [uploading, setUploading] = useState(false);
  const [dragging, setDragging] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [uploadDescription, setUploadDescription] = useState("");
  const [uploadCover, setUploadCover] = useState<File | null>(null);
  const [uploadCoverUrl, setUploadCoverUrl] = useState<string | null>(null);
  const [tocOpen, setTocOpen] = useState(false);
  const [viewMode, setViewMode] = useState<ViewMode>("book");
  const [modeMenuOpen, setModeMenuOpen] = useState(false);
  const [user, setUser] = useState<AuthUser | null>(() => loadUser());

  useEffect(() => {
    if (!uploadCover) {
      setUploadCoverUrl(null);
      return;
    }
    const url = URL.createObjectURL(uploadCover);
    setUploadCoverUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [uploadCover]);

  function resetBook() {
    setBookId(null);
    setPageCount(0);
    setPage(1);
    setMoods([]);
    setError(null);
    setView("landing");
  }

  function goToLibrary() {
    setView("library");
  }

  function backToLandingAndScrollUpload() {
    setView("landing");
    setTimeout(() => scrollToId("upload"), 60);
  }

  function openDetail(book: BookSummary) {
    setDetailBookId(book.id);
    setView("detail");
  }

  async function readBook(book: BookDetailData) {
    setError(null);
    try {
      const res = await fetch(`/books/${book.id}/moods`);
      if (!res.ok) throw new Error(`status ${res.status}`);
      const data = await res.json();
      setBookId(book.id);
      setPageCount(book.page_count);
      setPage(1);
      setMoods(data.moods ?? []);
      setView("reader");
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  const currentMood = moods[page - 1];
  const accent = moodAccent(currentMood?.mood);
  const accentStyle = moodToCss(accent) as CSSProperties;
  const pdfUrl = bookId ? `/books/${bookId}/pdf` : null;

  async function onUpload(selected: File) {
    if (!selected.type.includes("pdf") && !selected.name.toLowerCase().endsWith(".pdf")) {
      setError("PDF 파일만 업로드할 수 있어요.");
      return;
    }
    setError(null);
    setUploading(true);
    try {
      const fd = new FormData();
      fd.append("file", selected);
      const desc = uploadDescription.trim();
      if (desc) fd.append("description", desc);
      if (uploadCover) fd.append("cover", uploadCover);
      const res = await fetch("/upload", {
        method: "POST",
        body: fd,
        headers: { ...authHeaders(user) },
      });
      if (!res.ok) throw new Error(`업로드 실패 (${res.status})`);
      const data = await res.json();
      // Land on the detail page so the user can see their description,
      // wait for the music cache to finish, then click "읽기 시작".
      setDetailBookId(data.book_id);
      setUploadDescription("");
      setUploadCover(null);
      setView("detail");
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setUploading(false);
    }
  }

  return (
    <div className="app" style={accentStyle}>
      <div className="app-auth">
        <AuthButton user={user} onChange={setUser} />
      </div>
      {view === "landing" && (
        <section className="landing">
          <div className="hero">
            <iframe
              className="hero-spline"
              src="https://my.spline.design/magazineinaglasscase-Uj0riiE3tLa1iXZrAZLXyoyF/"
              title="3D background"
              loading="lazy"
              aria-hidden="true"
            />
            <div className="hero-overlay" aria-hidden="true" />
            <div className="hero-inner">
              <img
                className="hero-logo"
                src="/logo.png"
                alt="BOOK STORE"
                onError={(e) => {
                  (e.currentTarget as HTMLImageElement).style.display = "none";
                }}
              />
              <p className="hero-tagline">
                책에 맞춰 흐르는 <em>배경음악</em>과 함께 읽는 곳.
              </p>
              <p className="hero-sub">
                PDF를 올리면 페이지마다 분위기를 분석해 실시간으로 어울리는 음악을 만들어 들려드립니다.
                다른 사람이 올린 책도 자유롭게 읽을 수 있어요.
              </p>
              <div className="hero-actions">
                <button className="cta" onClick={() => scrollToId("upload")}>
                  내 책 올리기
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <line x1="5" y1="12" x2="19" y2="12" />
                    <polyline points="12 5 19 12 12 19" />
                  </svg>
                </button>
                <button className="cta-secondary" onClick={goToLibrary}>
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
                    <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2Z" />
                  </svg>
                  라이브러리 둘러보기
                </button>
              </div>
            </div>
            <div className="scroll-hint" aria-hidden="true">
              <span>scroll</span>
              <svg width="12" height="20" viewBox="0 0 12 20" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                <path d="M6 1v14" />
                <path d="m2 11 4 4 4-4" />
              </svg>
            </div>
          </div>

          <div id="how" className="section-how">
            <iframe
              className="section-spline"
              src="https://my.spline.design/particles-ulMJUF2sknfLZ01wBuywJOX9/"
              title="3D particles background"
              loading="lazy"
              aria-hidden="true"
            />
            <div className="section-overlay" aria-hidden="true" />
            <div className="section-how-content">
              <div className="section-eyebrow">how it works</div>
              <h2 className="section-title">
                세 단계면 <em>충분합니다.</em>
              </h2>
              <div className="steps">
                {STEPS.map((step) => (
                  <div key={step.n} className="step-card">
                    <div className="step-number">{step.n}</div>
                    <div className="step-icon">{step.icon}</div>
                    <h3 className="step-title">{step.title}</h3>
                    <p className="step-desc">{step.desc}</p>
                  </div>
                ))}
              </div>
            </div>
          </div>

          <div className="section section-narrow">
            <div className="section-eyebrow">before you start</div>
            <h2 className="section-title">
              읽기 전에 <em>알아두세요.</em>
            </h2>
            <div className="tips">
              <ul className="tips-list">
                {TIPS.map((tip, i) => (
                  <li key={i}>{tip}</li>
                ))}
              </ul>
            </div>
          </div>

          <div id="upload" className="section section-narrow section-upload">
            <div className="section-eyebrow">ready</div>
            <h2 className="section-title">
              책을 <em>올려보세요.</em>
            </h2>

            <div className="upload-extras">
              <div className="cover-picker">
                {uploadCoverUrl ? (
                  <div className="cover-preview">
                    <img src={uploadCoverUrl} alt="" />
                    <button
                      type="button"
                      className="cover-remove"
                      onClick={() => setUploadCover(null)}
                      aria-label="표지 제거"
                    >
                      ✕
                    </button>
                  </div>
                ) : (
                  <label className="cover-picker-empty">
                    <input
                      type="file"
                      accept="image/png,image/jpeg,image/webp,image/gif"
                      onChange={(e) => setUploadCover(e.target.files?.[0] ?? null)}
                    />
                    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                      <rect x="3" y="3" width="18" height="18" rx="2" />
                      <circle cx="9" cy="9" r="2" />
                      <path d="m21 15-5-5L5 21" />
                    </svg>
                    <span>표지 이미지<br />(선택)</span>
                  </label>
                )}
              </div>
              <textarea
                className="upload-description"
                placeholder="(선택) 책 소개를 적어주세요 — 작가, 줄거리, 추천 포인트…"
                value={uploadDescription}
                onChange={(e) => setUploadDescription(e.target.value)}
                rows={4}
                maxLength={4000}
              />
            </div>

            <label
              className={`drop-zone${dragging ? " dragging" : ""}`}
              onDragOver={(e) => {
                e.preventDefault();
                setDragging(true);
              }}
              onDragLeave={() => setDragging(false)}
              onDrop={(e) => {
                e.preventDefault();
                setDragging(false);
                const f = e.dataTransfer.files?.[0];
                if (f) onUpload(f);
              }}
            >
              <svg
                className="drop-zone-icon"
                width="32"
                height="32"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.5"
                strokeLinecap="round"
                strokeLinejoin="round"
              >
                <path d="M4 19V5a2 2 0 0 1 2-2h8l6 6v10a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2Z" />
                <path d="M14 3v6h6" />
                <path d="M12 13v5" />
                <path d="M9.5 15.5 12 13l2.5 2.5" />
              </svg>
              <div className="drop-zone-label">
                <strong>PDF 파일</strong>을 끌어다 놓거나 클릭해서 선택하세요
                <span className="drop-zone-hint">
                  업로드한 책은 라이브러리에 등록돼 다른 사람도 읽을 수 있어요
                </span>
              </div>
              <input
                type="file"
                accept="application/pdf"
                disabled={uploading}
                onChange={(e) => {
                  const f = e.target.files?.[0];
                  if (f) onUpload(f);
                }}
              />
            </label>

            {uploading && (
              <div className="uploading">
                <div className="spinner" />
                <span>페이지 분위기를 한 권 분량으로 분석 중…</span>
              </div>
            )}
            {error && (
              <div className="uploading">
                <span className="error">{error}</span>
              </div>
            )}
          </div>

          <footer className="landing-footer">
            <span>made with Gemini + Lyria RealTime</span>
          </footer>
        </section>
      )}

      {view === "library" && (
        <Library
          user={user}
          onSelectBook={openDetail}
          onBack={() => setView("landing")}
          onGoUpload={backToLandingAndScrollUpload}
        />
      )}

      {view === "detail" && detailBookId && (
        <BookDetail
          bookId={detailBookId}
          user={user}
          onBack={() => setView("library")}
          onRead={readBook}
          onDeleted={() => {
            setDetailBookId(null);
            setView("library");
          }}
        />
      )}

      {view === "reader" && bookId && (
        <section className="reader">
          <header className="reader-top">
            <div className="reader-top-nav">
              <button className="reader-brand" onClick={resetBook} title="시작 페이지로">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <line x1="19" y1="12" x2="5" y2="12" />
                  <polyline points="12 19 5 12 12 5" />
                </svg>
                <span>BOOK STORE</span>
              </button>
              <button className="reader-link" onClick={goToLibrary} title="라이브러리">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
                  <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2Z" />
                </svg>
                <span>라이브러리</span>
              </button>
              <button className="reader-link" onClick={() => setTocOpen(true)} title="목차">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                  <line x1="8" y1="6" x2="21" y2="6" />
                  <line x1="8" y1="12" x2="21" y2="12" />
                  <line x1="8" y1="18" x2="21" y2="18" />
                  <circle cx="4" cy="6" r="1" />
                  <circle cx="4" cy="12" r="1" />
                  <circle cx="4" cy="18" r="1" />
                </svg>
                <span>목차</span>
              </button>

              <div className="mode-picker">
                <button
                  className="reader-link"
                  onClick={() => setModeMenuOpen((v) => !v)}
                  title="보기 모드"
                  aria-expanded={modeMenuOpen}
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                    <rect x="3" y="3" width="7" height="7" rx="1" />
                    <rect x="14" y="3" width="7" height="7" rx="1" />
                    <rect x="3" y="14" width="7" height="7" rx="1" />
                    <rect x="14" y="14" width="7" height="7" rx="1" />
                  </svg>
                  <span>{VIEW_MODES.find((m) => m.id === viewMode)?.label ?? "Book"}</span>
                  <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <polyline points="6 9 12 15 18 9" />
                  </svg>
                </button>
                {modeMenuOpen && (
                  <>
                    <div
                      className="mode-menu-backdrop"
                      onClick={() => setModeMenuOpen(false)}
                      aria-hidden="true"
                    />
                    <div className="mode-menu" role="menu">
                      {VIEW_MODES.map((m) => (
                        <button
                          key={m.id}
                          className={`mode-menu-item${m.id === viewMode ? " mode-menu-item-active" : ""}`}
                          onClick={() => {
                            setViewMode(m.id);
                            setModeMenuOpen(false);
                          }}
                          role="menuitem"
                        >
                          <span className="mode-menu-label">{m.label}</span>
                          <span className="mode-menu-sub">{m.sub}</span>
                        </button>
                      ))}
                    </div>
                  </>
                )}
              </div>
            </div>
            <div className="reader-mood">
              <span>now playing</span>
              <span className="mood-label">{accent.label}</span>
            </div>
            <div
              className="progress-line"
              style={{ width: `${(page / Math.max(1, pageCount)) * 100}%` }}
            />
          </header>

          <div className={`reader-stage reader-stage-${viewMode}`}>
            {pdfUrl && viewMode === "book" && (
              <PdfViewer
                fileUrl={pdfUrl}
                page={page}
                pageCount={pageCount}
                onPageChange={setPage}
                onLoadError={(err) => {
                  console.error("PDF load failed", err);
                  resetBook();
                  setError(
                    "이 책은 서버에서 사라졌어요 (DB 초기화나 캐시 만료). 다시 업로드하거나 라이브러리에서 다른 책을 골라주세요."
                  );
                }}
              />
            )}
            {pdfUrl && (viewMode === "page" || viewMode === "scroll") && (
              <FlatPdfViewer
                fileUrl={pdfUrl}
                page={page}
                pageCount={pageCount}
                onPageChange={setPage}
                mode={viewMode}
                onLoadError={(err) => {
                  console.error("PDF load failed", err);
                  resetBook();
                  setError("PDF를 불러올 수 없어요. 다시 업로드하거나 다른 책을 골라주세요.");
                }}
              />
            )}
            {(viewMode === "novel" || viewMode === "comfort") && (
              <TextReader
                bookId={bookId}
                page={page}
                pageCount={pageCount}
                onPageChange={setPage}
                mode={viewMode}
              />
            )}
          </div>

          <AudioPlayer
            bookId={bookId}
            page={page}
            pageCount={pageCount}
            onPageChange={setPage}
            currentMood={currentMood}
            accentLabel={accent.label}
          />

          <TocPanel
            bookId={bookId}
            open={tocOpen}
            currentPage={page}
            onClose={() => setTocOpen(false)}
            onJump={(p) => setPage(p)}
          />
        </section>
      )}
    </div>
  );
}
