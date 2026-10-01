import { useEffect, useRef } from "react";

interface Props {
  open: boolean;
  music: number;
  tts: number;
  sfx: number;
  onClose: () => void;
  onChange: (kind: "music" | "tts" | "sfx", v: number) => void;
}

export function VolumePanel({ open, music, tts, sfx, onClose, onChange }: Props) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    function onDoc(e: MouseEvent) {
      if (!ref.current?.contains(e.target as Node)) onClose();
    }
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    // Defer attaching so the click that opens the panel doesn't immediately close it.
    const handle = window.setTimeout(() => {
      document.addEventListener("mousedown", onDoc);
    }, 0);
    window.addEventListener("keydown", onKey);
    return () => {
      window.clearTimeout(handle);
      document.removeEventListener("mousedown", onDoc);
      window.removeEventListener("keydown", onKey);
    };
  }, [open, onClose]);

  if (!open) return null;

  return (
    <div className="volume-panel" ref={ref} role="dialog" aria-label="음량 조절">
      <Slider
        label="음악"
        sub="Lyria 배경"
        value={music}
        onChange={(v) => onChange("music", v)}
        icon={
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
            <path d="M9 18V5l12-2v13" />
            <circle cx="6" cy="18" r="3" />
            <circle cx="18" cy="16" r="3" />
          </svg>
        }
      />
      <Slider
        label="낭독"
        sub="TTS 음성"
        value={tts}
        onChange={(v) => onChange("tts", v)}
        icon={
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
            <path d="M3 10v4a1 1 0 0 0 1 1h3l5 4V5L7 9H4a1 1 0 0 0-1 1Z" />
            <path d="M15.5 8.5a4 4 0 0 1 0 7" />
            <path d="M19 5a8 8 0 0 1 0 14" />
          </svg>
        }
      />
      <Slider
        label="효과음"
        sub="페이지 넘김 등"
        value={sfx}
        onChange={(v) => onChange("sfx", v)}
        icon={
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
            <path d="M11 5 6 9H2v6h4l5 4V5Z" />
            <line x1="17" y1="9" x2="22" y2="14" />
            <line x1="22" y1="9" x2="17" y2="14" />
          </svg>
        }
      />
    </div>
  );
}

function Slider({
  label,
  sub,
  value,
  onChange,
  icon,
}: {
  label: string;
  sub: string;
  value: number;
  onChange: (v: number) => void;
  icon: React.ReactNode;
}) {
  const pct = Math.round(value * 100);
  return (
    <div className="volume-row">
      <div className="volume-row-head">
        <span className="volume-icon">{icon}</span>
        <span className="volume-label">{label}</span>
        <span className="volume-sub">{sub}</span>
        <span className="volume-value">{pct}</span>
      </div>
      <input
        type="range"
        min={0}
        max={1}
        step={0.01}
        value={value}
        onChange={(e) => onChange(parseFloat(e.target.value))}
        className="volume-slider"
        style={{
          background: `linear-gradient(to right, var(--accent) 0%, var(--accent) ${pct}%, var(--border) ${pct}%, var(--border) 100%)`,
        }}
      />
    </div>
  );
}
