import { useEffect } from "react";
import type { AuthUser } from "../lib/auth";
import { saveUser } from "../lib/auth";

interface ViewModeItem {
  id: string;
  label: string;
  sub: string;
}

interface Props {
  open: boolean;
  onClose: () => void;
  user: AuthUser | null;
  onUserChange: (u: AuthUser | null) => void;

  // Theme
  theme: "dark" | "sepia" | "light";
  setTheme: (t: "dark" | "sepia" | "light") => void;

  // Reader-only props (omit when in library view)
  inReader?: boolean;

  // Panels — same setters App.tsx already uses
  onOpenToc?: () => void;
  onOpenChars?: () => void;
  onOpenSearch?: () => void;
  onOpenAsk?: () => void;
  onOpenHighlights?: () => void;
  onOpenImage?: () => void;
  onOpenVolume?: () => void;

  // Toggles
  ttsOn?: boolean;
  onToggleTts?: () => void;
  pomoOn?: boolean;
  onTogglePomo?: () => void;
  focusMode?: boolean;
  onToggleFocus?: () => void;

  // View mode picker
  viewMode?: string;
  viewModes?: ViewModeItem[];
  onChangeViewMode?: (id: string) => void;

  // Library actions
  onGoLibrary?: () => void;
  onGoHome?: () => void;
}

/** Single mobile settings sheet. Collapses the desktop top-nav (10+ icons)
 *  into one hamburger entry — much more usable on a phone. Bottom sheet
 *  style so the user's thumb already lives in that zone. */
export function MobileMenu({
  open,
  onClose,
  user,
  onUserChange,
  theme,
  setTheme,
  inReader,
  onOpenToc,
  onOpenChars,
  onOpenSearch,
  onOpenAsk,
  onOpenHighlights,
  onOpenImage,
  onOpenVolume,
  ttsOn,
  onToggleTts,
  pomoOn,
  onTogglePomo,
  focusMode,
  onToggleFocus,
  viewMode,
  viewModes,
  onChangeViewMode,
  onGoLibrary,
  onGoHome,
}: Props) {
  useEffect(() => {
    if (!open) return;
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    window.addEventListener("keydown", onKey);
    // Lock background scroll while the sheet is open.
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      window.removeEventListener("keydown", onKey);
      document.body.style.overflow = prev;
    };
  }, [open, onClose]);

  if (!open) return null;

  function cycleTheme() {
    setTheme(theme === "dark" ? "sepia" : theme === "sepia" ? "light" : "dark");
  }

  function tap(fn: (() => void) | undefined) {
    return () => {
      fn?.();
      onClose();
    };
  }

  function signOut() {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const w = window as any;
    try {
      w.google?.accounts?.id?.disableAutoSelect?.();
    } catch {
      /* ignore */
    }
    saveUser(null);
    onUserChange(null);
    onClose();
  }

  const themeLabel =
    theme === "dark" ? "다크" : theme === "sepia" ? "세피아" : "라이트";

  return (
    <>
      <div className="mm-backdrop" onClick={onClose} aria-hidden="true" />
      <div className="mm-sheet" role="dialog" aria-label="설정 메뉴">
        <div className="mm-grabber" />

        {user ? (
          <div className="mm-user">
            {user.picture ? (
              <img src={user.picture} alt="" referrerPolicy="no-referrer" />
            ) : (
              <span className="mm-user-fallback">
                {(user.name || "?")[0]}
              </span>
            )}
            <div className="mm-user-meta">
              <div className="mm-user-name">{user.name}</div>
              <div className="mm-user-email">{user.email}</div>
            </div>
            <button className="mm-signout" onClick={signOut}>
              로그아웃
            </button>
          </div>
        ) : (
          <div className="mm-user mm-user-anon">
            <div className="mm-user-meta">
              <div className="mm-user-name">로그인하지 않음</div>
              <div className="mm-user-email">
                상단의 G 버튼으로 로그인하세요
              </div>
            </div>
          </div>
        )}

        {inReader && viewModes && onChangeViewMode && (
          <div className="mm-section">
            <div className="mm-section-title">보기 모드</div>
            <div className="mm-view-grid">
              {viewModes.map((m) => (
                <button
                  key={m.id}
                  className={`mm-view-tile${
                    m.id === viewMode ? " mm-view-tile-on" : ""
                  }`}
                  onClick={() => {
                    onChangeViewMode(m.id);
                    onClose();
                  }}
                >
                  <span className="mm-view-label">{m.label}</span>
                  <span className="mm-view-sub">{m.sub}</span>
                </button>
              ))}
            </div>
          </div>
        )}

        {inReader && (
          <div className="mm-section">
            <div className="mm-section-title">책 도구</div>
            <div className="mm-grid">
              <MMItem
                icon={<TocIcon />}
                label="목차"
                onClick={tap(onOpenToc)}
              />
              <MMItem
                icon={<SearchIcon />}
                label="검색"
                onClick={tap(onOpenSearch)}
              />
              <MMItem
                icon={<CharsIcon />}
                label="인물"
                onClick={tap(onOpenChars)}
              />
              <MMItem
                icon={<AskIcon />}
                label="AI에게 묻기"
                onClick={tap(onOpenAsk)}
              />
              <MMItem
                icon={<HighlightIcon />}
                label="하이라이트"
                onClick={tap(onOpenHighlights)}
              />
              <MMItem
                icon={<ImageIcon />}
                label="장면 그림"
                onClick={tap(onOpenImage)}
              />
            </div>
          </div>
        )}

        {inReader && (
          <div className="mm-section">
            <div className="mm-section-title">읽기 설정</div>
            <div className="mm-toggle-list">
              <MMToggle
                label="포모도로"
                on={!!pomoOn}
                onClick={onTogglePomo}
              />
              <MMToggle
                label="집중 모드"
                on={!!focusMode}
                onClick={onToggleFocus}
              />
              <MMToggle label="고급 낭독" on={!!ttsOn} onClick={onToggleTts} />
              <button className="mm-row" onClick={tap(onOpenVolume)}>
                <span>음량</span>
                <ChevronIcon />
              </button>
            </div>
          </div>
        )}

        <div className="mm-section">
          <div className="mm-section-title">앱 설정</div>
          <div className="mm-toggle-list">
            <button className="mm-row" onClick={cycleTheme}>
              <span>테마</span>
              <span className="mm-row-value">{themeLabel}</span>
            </button>
            {onGoHome && (
              <button className="mm-row" onClick={tap(onGoHome)}>
                <span>홈으로</span>
                <ChevronIcon />
              </button>
            )}
            {onGoLibrary && (
              <button className="mm-row" onClick={tap(onGoLibrary)}>
                <span>라이브러리</span>
                <ChevronIcon />
              </button>
            )}
          </div>
        </div>

        <button className="mm-close" onClick={onClose}>
          닫기
        </button>
      </div>
    </>
  );
}

function MMItem({
  icon,
  label,
  onClick,
}: {
  icon: React.ReactNode;
  label: string;
  onClick: () => void;
}) {
  return (
    <button className="mm-tile" onClick={onClick}>
      <span className="mm-tile-icon">{icon}</span>
      <span className="mm-tile-label">{label}</span>
    </button>
  );
}

function MMToggle({
  label,
  on,
  onClick,
}: {
  label: string;
  on: boolean;
  onClick?: () => void;
}) {
  return (
    <button
      className={`mm-row mm-row-toggle${on ? " mm-row-toggle-on" : ""}`}
      onClick={onClick}
    >
      <span>{label}</span>
      <span className="mm-switch" aria-hidden="true">
        <span className="mm-switch-dot" />
      </span>
    </button>
  );
}

/* Inline SVG icons — keep the same visual language as the desktop top-nav. */
function TocIcon() {
  return (
    <svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <line x1="8" y1="6" x2="21" y2="6" />
      <line x1="8" y1="12" x2="21" y2="12" />
      <line x1="8" y1="18" x2="21" y2="18" />
      <circle cx="4" cy="6" r="1" />
      <circle cx="4" cy="12" r="1" />
      <circle cx="4" cy="18" r="1" />
    </svg>
  );
}
function SearchIcon() {
  return (
    <svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <circle cx="11" cy="11" r="7" />
      <line x1="21" y1="21" x2="16.5" y2="16.5" />
    </svg>
  );
}
function CharsIcon() {
  return (
    <svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <circle cx="9" cy="9" r="3" />
      <circle cx="17" cy="14" r="3" />
      <circle cx="6" cy="16" r="2.5" />
      <line x1="11.5" y1="10" x2="14.5" y2="13" />
      <line x1="9" y1="12" x2="7" y2="14" />
    </svg>
  );
}
function AskIcon() {
  return (
    <svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M12 3v3" />
      <path d="M3 12h3" />
      <path d="M12 18v3" />
      <path d="M18 12h3" />
      <circle cx="12" cy="12" r="3" />
    </svg>
  );
}
function HighlightIcon() {
  return (
    <svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="m9 11-6 6v3h3l6-6" />
      <path d="m12 8 6-6 4 4-6 6" />
    </svg>
  );
}
function ImageIcon() {
  return (
    <svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <rect x="3" y="3" width="18" height="18" rx="2" />
      <circle cx="8.5" cy="9" r="1.5" />
      <path d="m21 15-5-5L5 21" />
    </svg>
  );
}
function ChevronIcon() {
  return (
    <svg
      width="14"
      height="14"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <polyline points="9 6 15 12 9 18" />
    </svg>
  );
}
