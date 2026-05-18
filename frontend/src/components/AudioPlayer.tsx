import { useEffect, useRef, useState } from "react";

// Lyria RealTime outputs 48 kHz, stereo, signed 16-bit little-endian PCM.
const SAMPLE_RATE = 48000;
const CHANNELS = 2;
const VIS_BARS = 14;
const IDLE_BARS = Array.from({ length: VIS_BARS }, () => 2);
// If Lyria doesn't return a first audio chunk within this window, surface a clear error.
const FIRST_CHUNK_TIMEOUT_MS = 15000;

type Mood = {
  prompt?: string;
  mood?: string;
  bpm?: number;
};

type Status = "idle" | "connecting" | "generating" | "playing" | "error";

interface Props {
  bookId: string;
  page: number;
  pageCount: number;
  onPageChange: (page: number) => void;
  currentMood?: Mood;
  accentLabel: string;
}

export function AudioPlayer({
  bookId,
  page,
  pageCount,
  onPageChange,
  currentMood,
  accentLabel,
}: Props) {
  const wsRef = useRef<WebSocket | null>(null);
  const ctxRef = useRef<AudioContext | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  const nextStartRef = useRef(0);
  const rafRef = useRef<number | null>(null);
  const timeoutRef = useRef<number | null>(null);
  const firstChunkRef = useRef(false);
  const chunkCountRef = useRef(0);

  const [status, setStatus] = useState<Status>("idle");
  const [error, setError] = useState<string | null>(null);
  const [bars, setBars] = useState<number[]>(IDLE_BARS);
  const [chunkCount, setChunkCount] = useState(0);

  const playing = status === "generating" || status === "playing";

  function clearChunkTimeout() {
    if (timeoutRef.current != null) {
      clearTimeout(timeoutRef.current);
      timeoutRef.current = null;
    }
  }

  function teardown() {
    if (rafRef.current != null) cancelAnimationFrame(rafRef.current);
    rafRef.current = null;
    clearChunkTimeout();
    wsRef.current?.close();
    wsRef.current = null;
    ctxRef.current?.close().catch(() => {});
    ctxRef.current = null;
    analyserRef.current = null;
    nextStartRef.current = 0;
    firstChunkRef.current = false;
    chunkCountRef.current = 0;
    setBars(IDLE_BARS);
    setChunkCount(0);
    setStatus("idle");
  }

  function failWith(message: string) {
    clearChunkTimeout();
    if (rafRef.current != null) cancelAnimationFrame(rafRef.current);
    rafRef.current = null;
    wsRef.current?.close();
    wsRef.current = null;
    ctxRef.current?.close().catch(() => {});
    ctxRef.current = null;
    analyserRef.current = null;
    firstChunkRef.current = false;
    setBars(IDLE_BARS);
    setError(message);
    setStatus("error");
  }

  function tickVisualizer() {
    const analyser = analyserRef.current;
    if (!analyser) return;
    const data = new Uint8Array(analyser.frequencyBinCount);
    analyser.getByteFrequencyData(data);
    const step = Math.max(1, Math.floor(data.length / VIS_BARS));
    const next: number[] = [];
    for (let i = 0; i < VIS_BARS; i++) {
      let sum = 0;
      for (let j = 0; j < step; j++) sum += data[i * step + j] ?? 0;
      const avg = sum / step;
      next.push(2 + (avg / 255) * 20);
    }
    setBars(next);
    rafRef.current = requestAnimationFrame(tickVisualizer);
  }

  function start() {
    setError(null);
    setStatus("connecting");
    const ctx = new AudioContext({ sampleRate: SAMPLE_RATE });
    const analyser = ctx.createAnalyser();
    analyser.fftSize = 256;
    analyser.smoothingTimeConstant = 0.75;
    analyser.connect(ctx.destination);

    ctxRef.current = ctx;
    analyserRef.current = analyser;
    nextStartRef.current = ctx.currentTime + 0.1;

    const scheme = location.protocol === "https:" ? "wss" : "ws";
    const ws = new WebSocket(`${scheme}://${location.host}/ws/music/${bookId}`);
    ws.binaryType = "arraybuffer";

    ws.onopen = () => {
      ws.send(JSON.stringify({ type: "page", page: page - 1 }));
      setStatus("generating");
      rafRef.current = requestAnimationFrame(tickVisualizer);
      timeoutRef.current = window.setTimeout(() => {
        if (!firstChunkRef.current) {
          failWith(
            "15초 동안 Lyria로부터 음악이 도착하지 않았어요. uvicorn 터미널의 에러 로그를 확인해주세요. (보통 GEMINI_API_KEY 누락이나 Lyria RealTime 액세스 미허용)"
          );
        }
      }, FIRST_CHUNK_TIMEOUT_MS);
    };

    ws.onerror = () => {
      failWith("WebSocket 연결 오류 — 백엔드가 실행 중인지 확인해주세요.");
    };

    ws.onclose = (e) => {
      // Normal teardown vs unexpected close
      if (status === "playing" || status === "generating" || status === "connecting") {
        if (e.code !== 1000 && !firstChunkRef.current) {
          failWith(
            `백엔드가 연결을 닫았어요 (code ${e.code}). uvicorn 터미널의 에러 로그를 확인해주세요.`
          );
          return;
        }
      }
      teardown();
    };

    ws.onmessage = (e) => {
      // Text frames are control/error messages from the backend.
      if (typeof e.data === "string") {
        try {
          const msg = JSON.parse(e.data);
          if (msg.type === "error") {
            failWith(`백엔드 오류: ${msg.message}`);
          }
        } catch {
          // ignore non-JSON text
        }
        return;
      }

      // Binary frames are audio chunks.
      if (!firstChunkRef.current) {
        firstChunkRef.current = true;
        clearChunkTimeout();
        setStatus("playing");
      }
      chunkCountRef.current += 1;
      if (chunkCountRef.current % 8 === 0) {
        setChunkCount(chunkCountRef.current);
      }
      const pcm = new Int16Array(e.data as ArrayBuffer);
      const frames = Math.floor(pcm.length / CHANNELS);
      if (frames === 0) return;
      const buf = ctx.createBuffer(CHANNELS, frames, SAMPLE_RATE);
      for (let ch = 0; ch < CHANNELS; ch++) {
        const channel = buf.getChannelData(ch);
        for (let i = 0; i < frames; i++) {
          channel[i] = pcm[i * CHANNELS + ch] / 32768;
        }
      }
      const src = ctx.createBufferSource();
      src.buffer = buf;
      src.connect(analyser);
      const startAt = Math.max(ctx.currentTime, nextStartRef.current);
      src.start(startAt);
      nextStartRef.current = startAt + buf.duration;
    };

    wsRef.current = ws;
  }

  useEffect(() => {
    const ws = wsRef.current;
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: "page", page: page - 1 }));
    }
  }, [page]);

  useEffect(() => () => teardown(), []);

  const canPrev = page > 1;
  const canNext = page < pageCount;

  const promptText = currentMood?.prompt;
  const bpm = currentMood?.bpm;
  const generatedSec = Math.round((chunkCount * 2048) / SAMPLE_RATE);

  return (
    <>
      {status !== "idle" && (
        <div className={`dock-status status-${status}`}>
          {(status === "connecting" || status === "generating") && (
            <span className="spinner-sm" aria-hidden="true" />
          )}
          {status === "playing" && (
            <span className="status-live" aria-hidden="true">
              <span className="status-live-dot" />
              LIVE
            </span>
          )}
          <span className="dock-status-text">
            {status === "connecting" && "음악 엔진 연결 중…"}
            {status === "generating" && "Lyria가 첫 음을 생성하고 있어요… (보통 2~5초)"}
            {status === "playing" && (
              <span className="status-prompt" key={promptText ?? ""}>
                <span className="status-prompt-text">{promptText ?? "재생 중"}</span>
                {bpm ? <span className="status-meta">{bpm} BPM</span> : null}
                <span className="status-meta">{generatedSec}s 생성됨</span>
              </span>
            )}
            {status === "error" && (error ?? "연결 오류")}
          </span>
          {status === "error" && (
            <button className="dock-status-close" onClick={() => setStatus("idle")} aria-label="닫기">
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <line x1="18" y1="6" x2="6" y2="18" />
                <line x1="6" y1="6" x2="18" y2="18" />
              </svg>
            </button>
          )}
        </div>
      )}

      <div className="dock">
        <div className="dock-group">
          <button
            className="icon-btn"
            aria-label="이전 페이지"
            onClick={() => onPageChange(Math.max(1, page - 1))}
            disabled={!canPrev}
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <polyline points="15 18 9 12 15 6" />
            </svg>
          </button>
          <span className="page-counter">
            {page} / {pageCount}
          </span>
          <button
            className="icon-btn"
            aria-label="다음 페이지"
            onClick={() => onPageChange(Math.min(pageCount, page + 1))}
            disabled={!canNext}
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <polyline points="9 18 15 12 9 6" />
            </svg>
          </button>
        </div>

        <div className="dock-divider" />

        <div className="dock-group">
          <button
            className="icon-btn primary"
            aria-label={playing ? "음악 정지" : "음악 시작"}
            onClick={playing ? teardown : start}
          >
            {status === "connecting" || status === "generating" ? (
              <span className="spinner-sm spinner-on-btn" />
            ) : playing ? (
              <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
                <rect x="6" y="5" width="4" height="14" rx="1" />
                <rect x="14" y="5" width="4" height="14" rx="1" />
              </svg>
            ) : (
              <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
                <path d="M7 4.5v15l13-7.5z" />
              </svg>
            )}
          </button>
          <div className="visualizer" aria-hidden="true">
            {bars.map((h, i) => (
              <div
                key={i}
                className="visualizer-bar"
                style={{ height: `${h}px`, opacity: playing ? 0.78 : 0.25 }}
              />
            ))}
          </div>
        </div>

        <div className="dock-divider" />

        <div className="mood-badge" title={currentMood?.prompt ?? ""}>
          <div className="mood-dot" />
          <span className="mood-label">{accentLabel}</span>
        </div>
      </div>
    </>
  );
}
