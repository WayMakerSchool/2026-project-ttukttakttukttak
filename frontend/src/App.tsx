import { useEffect, useState, type CSSProperties } from "react";
import { PdfViewer } from "./components/PdfViewer";
import { AudioPlayer } from "./components/AudioPlayer";
import { Library, type BookSummary } from "./components/Library";
import { BookDetail, type BookDetailData } from "./components/BookDetail";
import { TocPanel } from "./components/TocPanel";
import { ChapterImageModal } from "./components/ChapterImageModal";
import { UploadWizard } from "./components/UploadWizard";
import { FlatPdfViewer } from "./components/FlatPdfViewer";
import { TextReader } from "./components/TextReader";
import { AuthButton } from "./components/AuthButton";
import { TtsPlayer } from "./components/TtsPlayer";
import { HighlightsPanel } from "./components/HighlightsPanel";
import { AskPanel } from "./components/AskPanel";
import { ResumeRecap } from "./components/ResumeRecap";
import { SearchPanel } from "./components/SearchPanel";
import { Pomodoro } from "./components/Pomodoro";
import { CharactersPanel } from "./components/CharactersPanel";
import { VolumePanel } from "./components/VolumePanel";
import { MobileMenu } from "./components/MobileMenu";
import { CameraReader } from "./components/CameraReader";
import { Legal } from "./components/Legal";
import { authHeaders, loadUser, type AuthUser } from "./lib/auth";
import { authQuery, progressHeaders } from "./lib/client-id";
import { moodAccent, moodToCss } from "./lib/mood-colors";

type Mood = {
  instruments?: string[];
  genre?: string;
  bpm?: number;
  mood?: string;
  intensity?: number;
  prompt?: string;
};

type View = "landing" | "library" | "detail" | "reader" | "camera" | "legal";
type ViewMode = "book" | "page" | "scroll" | "novel" | "comfort";
type Theme = "dark" | "sepia" | "light";

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

// Click-wrap consent — checked once on first visit. Stores the acceptance
// timestamp in localStorage; users only see the legal screen once.
const LEGAL_ACCEPT_KEY = "book_store_legal_accepted_at";

export default function App() {
  const [legalAccepted, setLegalAccepted] = useState<boolean>(
    () => !!localStorage.getItem(LEGAL_ACCEPT_KEY)
  );
  const [view, setView] = useState<View>("landing");
  const [bookId, setBookId] = useState<string | null>(null);
  const [detailBookId, setDetailBookId] = useState<string | null>(null);
  const [pageCount, setPageCount] = useState(0);
  const [page, setPage] = useState(1);
  const [moods, setMoods] = useState<Mood[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [tocOpen, setTocOpen] = useState(false);
  const [highlightsOpen, setHighlightsOpen] = useState(false);
  const [highlightsKey, setHighlightsKey] = useState(0);
  const [askOpen, setAskOpen] = useState(false);
  const [showRecap, setShowRecap] = useState(false);
  const [focusMode, setFocusMode] = useState(false);
  const [searchOpen, setSearchOpen] = useState(false);
  const [pomoOn, setPomoOn] = useState(false);
  const [charsOpen, setCharsOpen] = useState(false);
  const [volumeOpen, setVolumeOpen] = useState(false);
  const [imageOpen, setImageOpen] = useState(false);
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [musicVolume, setMusicVolume] = useState<number>(() => {
    const v = parseFloat(localStorage.getItem("vol_music") ?? "0.8");
    return isNaN(v) ? 0.8 : Math.max(0, Math.min(1, v));
  });
  const [ttsVolume, setTtsVolume] = useState<number>(() => {
    const v = parseFloat(localStorage.getItem("vol_tts") ?? "0.95");
    return isNaN(v) ? 0.95 : Math.max(0, Math.min(1, v));
  });
  const [sfxVolume, setSfxVolume] = useState<number>(() => {
    const v = parseFloat(localStorage.getItem("vol_sfx") ?? "0.55");
    return isNaN(v) ? 0.55 : Math.max(0, Math.min(1, v));
  });

  function setVolume(kind: "music" | "tts" | "sfx", v: number) {
    if (kind === "music") {
      setMusicVolume(v);
      localStorage.setItem("vol_music", String(v));
    } else if (kind === "tts") {
      setTtsVolume(v);
      localStorage.setItem("vol_tts", String(v));
    } else {
      setSfxVolume(v);
      localStorage.setItem("vol_sfx", String(v));
    }
  }
  const [viewMode, setViewMode] = useState<ViewMode>("book");
  const [modeMenuOpen, setModeMenuOpen] = useState(false);
  const [user, setUser] = useState<AuthUser | null>(() => loadUser());
  const [ttsOn, setTtsOn] = useState(false);
  const [theme, setTheme] = useState<Theme>(() => {
    const stored = localStorage.getItem("book_store_theme") as Theme | null;
    return stored === "sepia" || stored === "light" ? stored : "dark";
  });
  const [readingSeconds, setReadingSeconds] = useState(0);

  // Apply theme to <html> so all CSS vars cascade correctly.
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem("book_store_theme", theme);
  }, [theme]);

  // Reading timer — accumulate seconds while the user is in the reader view,
  // push to backend every 60s so partial sessions still count.
  useEffect(() => {
    if (view !== "reader" || !bookId) return;
    let local = 0;
    const tick = window.setInterval(() => {
      // Don't accrue while the tab is hidden.
      if (document.hidden) return;
      local += 5;
      setReadingSeconds((s) => s + 5);
    }, 5000);
    const flush = window.setInterval(() => {
      if (local <= 0) return;
      const seconds = local;
      local = 0;
      fetch("/me/sessions", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          ...progressHeaders(),
          ...authHeaders(user),
        },
        body: JSON.stringify({ book_id: bookId, seconds }),
      }).catch(() => {});
    }, 60000);
    return () => {
      window.clearInterval(tick);
      window.clearInterval(flush);
      if (local > 0) {
        navigator.sendBeacon?.(
          "/me/sessions",
          new Blob(
            [JSON.stringify({ book_id: bookId, seconds: local })],
            { type: "application/json" }
          )
        );
      }
    };
  }, [view, bookId, user]);

  // URL hash routing — `#/book/{id}` deep-links to a detail page.
  useEffect(() => {
    function readHash() {
      const h = window.location.hash;
      const m = h.match(/^#\/book\/([a-z0-9]+)/i);
      if (m && m[1]) {
        setDetailBookId(m[1]);
        setView("detail");
      }
    }
    readHash();
    window.addEventListener("hashchange", readHash);
    return () => window.removeEventListener("hashchange", readHash);
  }, []);
  const [zoom, setZoom] = useState<number>(() => {
    const raw = parseFloat(localStorage.getItem("book_store_zoom") ?? "1");
    return isNaN(raw) ? 1 : Math.max(0.6, Math.min(2, raw));
  });

  function clampZoom(z: number): number {
    return Math.max(0.6, Math.min(2, Math.round(z * 20) / 20));
  }

  function changeZoom(delta: number) {
    setZoom((z) => {
      const next = clampZoom(z + delta);
      localStorage.setItem("book_store_zoom", String(next));
      return next;
    });
  }

  function resetZoom() {
    setZoom(1);
    localStorage.setItem("book_store_zoom", "1");
  }

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return;
      if (!(e.metaKey || e.ctrlKey)) return;
      if (e.key === "=" || e.key === "+") {
        e.preventDefault();
        changeZoom(0.1);
      } else if (e.key === "-" || e.key === "_") {
        e.preventDefault();
        changeZoom(-0.1);
      } else if (e.key === "0") {
        e.preventDefault();
        resetZoom();
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

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
      const [moodsRes, progRes] = await Promise.all([
        fetch(`/books/${book.id}/moods`),
        fetch(`/books/${book.id}/progress`, {
          headers: { ...progressHeaders(), ...authHeaders(user) },
        }),
      ]);
      if (!moodsRes.ok) throw new Error(`status ${moodsRes.status}`);
      const moodsData = await moodsRes.json();
      let startPage = 1;
      if (progRes.ok) {
        const p = await progRes.json();
        if (typeof p.page === "number" && p.page >= 1 && p.page <= book.page_count) {
          startPage = p.page;
        }
      }
      setBookId(book.id);
      setPageCount(book.page_count);
      setPage(startPage);
      setMoods(moodsData.moods ?? []);
      setShowRecap(startPage > 1);
      setView("reader");
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  // Persist reading position. Debounced so rapid page-flipping doesn't spam.
  useEffect(() => {
    if (view !== "reader" || !bookId) return;
    const handle = window.setTimeout(() => {
      fetch(`/books/${bookId}/progress`, {
        method: "PUT",
        headers: {
          "Content-Type": "application/json",
          ...progressHeaders(),
          ...authHeaders(user),
        },
        body: JSON.stringify({ page }),
      }).catch(() => {});
    }, 1500);
    return () => window.clearTimeout(handle);
  }, [page, bookId, view, user]);

  const currentMood = moods[page - 1];
  const accent = moodAccent(currentMood?.mood);
  const accentStyle = moodToCss(accent) as CSSProperties;
  const pdfUrl = bookId ? `/books/${bookId}/pdf${authQuery(user?.idToken)}` : null;

  if (!legalAccepted) {
    return (
      <div className="app app--legal-consent">
        <Legal
          requireConsent
          onBack={() => {}}
          onConsent={() => {
            localStorage.setItem(LEGAL_ACCEPT_KEY, new Date().toISOString());
            setLegalAccepted(true);
          }}
        />
      </div>
    );
  }

  return (
    <div className={`app app--${view}`} style={accentStyle}>
      <div className="app-auth">
        <button
          type="button"
          className="theme-toggle"
          onClick={() =>
            setTheme((t) => (t === "dark" ? "sepia" : t === "sepia" ? "light" : "dark"))
          }
          title={`테마: ${theme} (클릭해서 전환)`}
          aria-label="테마 전환"
        >
          {theme === "dark" ? (
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
              <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
            </svg>
          ) : theme === "sepia" ? (
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
              <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
              <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2Z" />
            </svg>
          ) : (
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="12" cy="12" r="5" />
              <path d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42" />
            </svg>
          )}
        </button>
        <AuthButton user={user} onChange={setUser} />
      </div>
      {view === "landing" && (
        <section className="landing">
          <div className="hero">
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
                <button className="cta-secondary" onClick={() => setView("camera")}>
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z" />
                    <circle cx="12" cy="13" r="4" />
                  </svg>
                  카메라 리더
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

            <UploadWizard
              user={user}
              onUploaded={(id) => {
                setDetailBookId(id);
                setView("detail");
              }}
              onShowLegal={() => setView("legal")}
            />
          </div>

          <footer className="landing-footer">
            <span>made with Gemini + Lyria RealTime</span>
            <span className="landing-footer-sep">·</span>
            <button
              type="button"
              className="landing-footer-link"
              onClick={() => setView("legal")}
            >
              이용약관 · 개인정보처리방침
            </button>
            <span className="landing-footer-sep">·</span>
            <a
              className="landing-footer-link"
              href="mailto:zipkyh@gmail.com?subject=%5BBOOK%20STORE%5D%20%EC%A0%80%EC%9E%91%EA%B6%8C%20%EC%B9%A8%ED%95%B4%20%EC%8B%A0%EA%B3%A0"
            >
              저작권 침해 신고
            </a>
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

      {view === "camera" && (
        <CameraReader onBack={() => setView("landing")} />
      )}

      {view === "legal" && (
        <Legal onBack={() => setView("landing")} />
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
        <section className={`reader${focusMode ? " reader-focus" : ""}`}>
          <header className="reader-top">
            <div className="reader-top-nav">
              <button className="reader-brand" onClick={resetBook} title="시작 페이지로">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <line x1="19" y1="12" x2="5" y2="12" />
                  <polyline points="12 19 5 12 12 5" />
                </svg>
                <span>BOOK STORE</span>
              </button>
              {/* Mobile-only hamburger — collapses the whole top-nav into a
                  bottom sheet so a phone doesn't have to fit 10+ icons. */}
              <button
                className="reader-menu-mobile"
                onClick={() => setMobileMenuOpen(true)}
                title="메뉴"
                aria-label="메뉴"
              >
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <line x1="3" y1="6" x2="21" y2="6" />
                  <line x1="3" y1="12" x2="21" y2="12" />
                  <line x1="3" y1="18" x2="21" y2="18" />
                </svg>
              </button>
              <button className="reader-link" onClick={goToLibrary} title="라이브러리">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
                  <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2Z" />
                </svg>
                <span>라이브러리</span>
              </button>
              <button
                className="reader-link"
                onClick={() => setCharsOpen(true)}
                title="인물 관계도"
              >
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                  <circle cx="9" cy="9" r="3" />
                  <circle cx="17" cy="14" r="3" />
                  <circle cx="6" cy="16" r="2.5" />
                  <line x1="11.5" y1="10" x2="14.5" y2="13" />
                  <line x1="9" y1="12" x2="7" y2="14" />
                </svg>
                <span>인물</span>
              </button>

              <button
                className="reader-link"
                onClick={() => setSearchOpen(true)}
                title="책 내 검색"
              >
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                  <circle cx="11" cy="11" r="7" />
                  <line x1="21" y1="21" x2="16.5" y2="16.5" />
                </svg>
                <span>검색</span>
              </button>

              <div className="volume-wrap">
                <button
                  className={`reader-link${volumeOpen ? " reader-link-active" : ""}`}
                  onClick={() => setVolumeOpen((v) => !v)}
                  title="음량 조절"
                  aria-expanded={volumeOpen}
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M11 5 6 9H2v6h4l5 4V5Z" />
                    <path d="M15.5 8.5a4 4 0 0 1 0 7" />
                    <path d="M19 5a8 8 0 0 1 0 14" />
                  </svg>
                  <span>음량</span>
                </button>
                <VolumePanel
                  open={volumeOpen}
                  music={musicVolume}
                  tts={ttsVolume}
                  sfx={sfxVolume}
                  onClose={() => setVolumeOpen(false)}
                  onChange={setVolume}
                />
              </div>

              <button
                className={`reader-link${pomoOn ? " reader-link-active" : ""}`}
                onClick={() => setPomoOn((v) => !v)}
                title={pomoOn ? "포모도로 끄기" : "포모도로 타이머"}
                aria-pressed={pomoOn}
              >
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                  <circle cx="12" cy="13" r="8" />
                  <path d="M12 9v4l2 2" />
                  <path d="M9 2h6" />
                </svg>
                <span>포모도로</span>
              </button>

              <button
                className={`reader-link${focusMode ? " reader-link-active" : ""}`}
                onClick={() => setFocusMode((v) => !v)}
                title={focusMode ? "집중 모드 끄기" : "집중 모드 (UI 자동 페이드)"}
                aria-pressed={focusMode}
              >
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                  <circle cx="12" cy="12" r="3" />
                  <path d="M3 12s4-7 9-7 9 7 9 7-4 7-9 7-9-7-9-7z" />
                </svg>
                <span>{focusMode ? "집중 ON" : "집중"}</span>
              </button>

              <button
                className="reader-link reader-link-ai"
                onClick={() => setAskOpen(true)}
                title="이 책에게 묻기"
              >
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M12 3v3" />
                  <path d="m5.6 5.6 2.1 2.1" />
                  <path d="M3 12h3" />
                  <path d="m5.6 18.4 2.1-2.1" />
                  <path d="M12 18v3" />
                  <path d="m16.3 16.3 2.1 2.1" />
                  <path d="M18 12h3" />
                  <path d="m16.3 7.7 2.1-2.1" />
                  <circle cx="12" cy="12" r="3" />
                </svg>
                <span>AI에게 묻기</span>
              </button>

              <button
                className="reader-link"
                onClick={() => setHighlightsOpen(true)}
                title="하이라이트"
              >
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
                  <path d="m9 11-6 6v3h3l6-6" />
                  <path d="m12 8 6-6 4 4-6 6" />
                </svg>
                <span>하이라이트</span>
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

              <button
                className="reader-link"
                onClick={() => setImageOpen(true)}
                title="이 장면을 AI로 그려보기"
              >
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                  <rect x="3" y="3" width="18" height="18" rx="2" />
                  <circle cx="8.5" cy="9" r="1.5" />
                  <path d="m21 15-5-5L5 21" />
                </svg>
                <span>사진 만들기</span>
              </button>

              <button
                className={`reader-link tts-toggle${ttsOn ? " tts-toggle-on" : ""}`}
                onClick={() => setTtsOn((v) => !v)}
                title={ttsOn ? "고급 낭독 끄기" : "Gemini TTS 낭독 켜기 (페이지마다 비용 발생)"}
                aria-pressed={ttsOn}
              >
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M3 10v4a1 1 0 0 0 1 1h3l5 4V5L7 9H4a1 1 0 0 0-1 1Z" />
                  <path d="M15.5 8.5a4 4 0 0 1 0 7" />
                  <path d="M19 5a8 8 0 0 1 0 14" />
                </svg>
                <span>{ttsOn ? "낭독 ON" : "고급 낭독"}</span>
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
                zoom={zoom}
                sfxVolume={sfxVolume}
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
                zoom={zoom}
                mode={viewMode}
                sfxVolume={sfxVolume}
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
                zoom={zoom}
                mode={viewMode}
                user={user}
                onHighlighted={() => setHighlightsKey((k) => k + 1)}
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
            zoom={zoom}
            onZoomIn={() => changeZoom(0.1)}
            onZoomOut={() => changeZoom(-0.1)}
            onZoomReset={resetZoom}
            volume={musicVolume}
          />

          <TocPanel
            bookId={bookId}
            open={tocOpen}
            currentPage={page}
            onClose={() => setTocOpen(false)}
            onJump={(p) => setPage(p)}
          />

          <TtsPlayer bookId={bookId} page={page} enabled={ttsOn} volume={ttsVolume} />

          <HighlightsPanel
            bookId={bookId}
            open={highlightsOpen}
            refreshKey={highlightsKey}
            user={user}
            onClose={() => setHighlightsOpen(false)}
            onJump={(p) => setPage(p)}
          />

          <AskPanel
            bookId={bookId}
            open={askOpen}
            page={page}
            onClose={() => setAskOpen(false)}
          />

          {showRecap && (
            <ResumeRecap
              bookId={bookId}
              user={user}
              onResume={(p) => {
                setPage(p);
                setShowRecap(false);
              }}
              onStartOver={() => {
                setPage(1);
                setShowRecap(false);
              }}
            />
          )}

          <SearchPanel
            bookId={bookId}
            open={searchOpen}
            onClose={() => setSearchOpen(false)}
            onJump={(p) => setPage(p)}
          />

          <CharactersPanel
            bookId={bookId}
            open={charsOpen}
            onClose={() => setCharsOpen(false)}
            onJump={(p) => setPage(p)}
          />

          <ChapterImageModal
            bookId={bookId}
            page={page}
            open={imageOpen}
            onClose={() => setImageOpen(false)}
          />

          {pomoOn && <Pomodoro />}

          <MobileMenu
            open={mobileMenuOpen}
            onClose={() => setMobileMenuOpen(false)}
            user={user}
            onUserChange={setUser}
            theme={theme}
            setTheme={setTheme}
            inReader
            onOpenToc={() => setTocOpen(true)}
            onOpenChars={() => setCharsOpen(true)}
            onOpenSearch={() => setSearchOpen(true)}
            onOpenAsk={() => setAskOpen(true)}
            onOpenHighlights={() => setHighlightsOpen(true)}
            onOpenImage={() => setImageOpen(true)}
            onOpenVolume={() => setVolumeOpen(true)}
            ttsOn={ttsOn}
            onToggleTts={() => setTtsOn((v) => !v)}
            pomoOn={pomoOn}
            onTogglePomo={() => setPomoOn((v) => !v)}
            focusMode={focusMode}
            onToggleFocus={() => setFocusMode((v) => !v)}
            viewMode={viewMode}
            viewModes={VIEW_MODES}
            onChangeViewMode={(id) => setViewMode(id as ViewMode)}
            onGoLibrary={goToLibrary}
            onGoHome={resetBook}
          />
        </section>
      )}
    </div>
  );
}
