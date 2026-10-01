import { useEffect, useRef, useState } from "react";

type Phase = "idle" | "focus" | "break";

const FOCUS_MINUTES = 25;
const BREAK_MINUTES = 5;

function fmt(secs: number): string {
  const m = Math.floor(secs / 60);
  const s = secs % 60;
  return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}

export function Pomodoro() {
  const [phase, setPhase] = useState<Phase>("idle");
  const [seconds, setSeconds] = useState(FOCUS_MINUTES * 60);
  const [collapsed, setCollapsed] = useState(false);
  const intervalRef = useRef<number | null>(null);

  useEffect(() => {
    if (phase === "idle") return;
    intervalRef.current = window.setInterval(() => {
      setSeconds((s) => Math.max(0, s - 1));
    }, 1000);
    return () => {
      if (intervalRef.current != null) window.clearInterval(intervalRef.current);
    };
  }, [phase]);

  // When the timer hits 0, swap phases + nudge with a soft beep.
  useEffect(() => {
    if (seconds > 0) return;
    if (phase === "focus") {
      playChime("up");
      setPhase("break");
      setSeconds(BREAK_MINUTES * 60);
    } else if (phase === "break") {
      playChime("down");
      setPhase("focus");
      setSeconds(FOCUS_MINUTES * 60);
    }
  }, [seconds, phase]);

  function start() {
    setPhase("focus");
    setSeconds(FOCUS_MINUTES * 60);
  }

  function stop() {
    setPhase("idle");
    setSeconds(FOCUS_MINUTES * 60);
  }

  function skipPhase() {
    if (phase === "focus") {
      playChime("up");
      setPhase("break");
      setSeconds(BREAK_MINUTES * 60);
    } else if (phase === "break") {
      playChime("down");
      setPhase("focus");
      setSeconds(FOCUS_MINUTES * 60);
    }
  }

  const total = (phase === "break" ? BREAK_MINUTES : FOCUS_MINUTES) * 60;
  const progress = phase === "idle" ? 0 : 1 - seconds / total;

  if (collapsed) {
    return (
      <button
        type="button"
        className="pomo-pill pomo-collapsed"
        onClick={() => setCollapsed(false)}
        title="포모도로"
      >
        ⏱
      </button>
    );
  }

  return (
    <div className={`pomo-pill pomo-${phase}`} role="status" aria-live="polite">
      <svg className="pomo-ring" width="40" height="40" viewBox="0 0 40 40">
        <circle cx="20" cy="20" r="17" className="pomo-ring-bg" />
        <circle
          cx="20"
          cy="20"
          r="17"
          className="pomo-ring-fill"
          style={{
            strokeDasharray: `${2 * Math.PI * 17}`,
            strokeDashoffset: `${(1 - progress) * 2 * Math.PI * 17}`,
          }}
        />
      </svg>

      <div className="pomo-body">
        <div className="pomo-label">
          {phase === "idle" ? "포모도로" : phase === "focus" ? "집중" : "휴식"}
        </div>
        <div className="pomo-time">{fmt(seconds)}</div>
      </div>

      <div className="pomo-actions">
        {phase === "idle" ? (
          <button onClick={start} aria-label="시작" title="시작">▶</button>
        ) : (
          <>
            <button onClick={skipPhase} aria-label="다음 단계" title="다음 단계">⏭</button>
            <button onClick={stop} aria-label="중지" title="중지">■</button>
          </>
        )}
        <button
          onClick={() => setCollapsed(true)}
          aria-label="최소화"
          title="최소화"
          className="pomo-minimize"
        >
          –
        </button>
      </div>
    </div>
  );
}

function playChime(kind: "up" | "down") {
  try {
    const ctx = new AudioContext();
    const o = ctx.createOscillator();
    const g = ctx.createGain();
    o.connect(g);
    g.connect(ctx.destination);
    o.type = "sine";
    const base = kind === "up" ? 660 : 440;
    o.frequency.setValueAtTime(base, ctx.currentTime);
    o.frequency.exponentialRampToValueAtTime(base * 1.5, ctx.currentTime + 0.3);
    g.gain.setValueAtTime(0.0001, ctx.currentTime);
    g.gain.exponentialRampToValueAtTime(0.2, ctx.currentTime + 0.05);
    g.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + 0.6);
    o.start();
    o.stop(ctx.currentTime + 0.7);
    setTimeout(() => ctx.close().catch(() => {}), 800);
  } catch {
    /* ignore */
  }
}
