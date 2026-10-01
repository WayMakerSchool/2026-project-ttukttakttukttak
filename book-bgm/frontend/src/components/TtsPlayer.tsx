import { useEffect, useRef, useState } from "react";

interface Props {
  bookId: string;
  page: number;
  enabled: boolean;
  volume?: number;
}

/** Headless component: when `enabled` is true, fetches Gemini-generated TTS
 *  for the current page and plays it. Auto-stops + re-fetches on page change. */
export function TtsPlayer({ bookId, page, enabled, volume = 0.95 }: Props) {
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const [status, setStatus] = useState<"idle" | "loading" | "playing" | "error">(
    "idle"
  );
  const [errMsg, setErrMsg] = useState<string | null>(null);

  useEffect(() => {
    // Always stop whatever was playing whenever inputs change.
    audioRef.current?.pause();
    audioRef.current = null;

    if (!enabled) {
      setStatus("idle");
      return;
    }

    let cancelled = false;
    setStatus("loading");
    setErrMsg(null);

    const audio = new Audio(`/books/${bookId}/pages/${page}/tts`);
    audio.volume = volume;
    audio.preload = "auto";
    audio.onplaying = () => {
      if (!cancelled) setStatus("playing");
    };
    audio.onerror = () => {
      if (!cancelled) {
        setStatus("error");
        setErrMsg("낭독 오디오를 불러올 수 없어요. 백엔드 로그 확인이 필요해요.");
      }
    };
    audio.onended = () => {
      if (!cancelled) setStatus("idle");
    };

    audio.play().catch((e) => {
      if (cancelled) return;
      setStatus("error");
      setErrMsg(e?.message || "재생 차단됨 (브라우저 자동재생 제한)");
    });

    audioRef.current = audio;

    return () => {
      cancelled = true;
      audio.pause();
      audio.src = "";
      audioRef.current = null;
    };
  }, [bookId, page, enabled]);

  // Live-update volume on slider drag without re-fetching the audio.
  useEffect(() => {
    if (audioRef.current) {
      audioRef.current.volume = volume;
    }
  }, [volume]);

  if (!enabled) return null;
  if (status === "loading") {
    return (
      <div className="tts-status">
        <div className="spinner-sm" />
        <span>낭독 준비 중…</span>
      </div>
    );
  }
  if (status === "error") {
    return (
      <div className="tts-status tts-status-error">
        <span>{errMsg ?? "낭독 오류"}</span>
      </div>
    );
  }
  if (status === "playing") {
    return (
      <div className="tts-status tts-status-playing">
        <span className="tts-pulse" />
        <span>낭독 중 · p.{page}</span>
      </div>
    );
  }
  return null;
}
