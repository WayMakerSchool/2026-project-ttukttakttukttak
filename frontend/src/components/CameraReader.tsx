import { useEffect, useRef, useState } from "react";
import { loadUser } from "../lib/auth";
import { getClientId } from "../lib/client-id";
import { getGeo } from "../lib/geo";
import { CharactersPanel } from "./CharactersPanel";

const SAMPLE_RATE = 48000;
const CHANNELS = 2;

type Chapter = {
  idx: number;
  title: string;
  summary: string;
  mood: string;
  bpm: number;
};

type Detection = {
  chapter_idx: number;
  confidence: number;
  evidence: string;
};

type Session = {
  id: string;
  book_name: string;
  author?: string;
  publisher?: string;
  translator?: string;
  edition?: string;
  matched_edition?: string;
  toc: Chapter[];
  current_chapter_idx: number;
  audio_status: "pending" | "generating" | "ready" | "failed";
  ready_segments: number[];
  last_detection: Detection | null;
  camera_server: string;
};

type CoverInfo = {
  title: string;
  author: string;
  publisher: string;
  translator: string;
  edition: string;
  isbn?: string;
  confidence: number;
  evidence: string;
  toc?: Chapter[];
  matched_edition?: string;
  toc_error?: string;
};

// Where the currently displayed TOC came from. Strictly ordered by trust:
// photo (shot of the printed 목차 page) > store (bookstore verbatim) >
// llm (AI-knowledge guess). Upgrades may only move up this ladder — the
// background store upgrade must never overwrite a photographed TOC.
type TocSource = "llm" | "store" | "photo";

type CamMode = "phone" | "board";

type Props = { onBack: () => void };

export function CameraReader({ onBack }: Props) {
  const [bookName, setBookName] = useState("");
  const [cover, setCover] = useState<CoverInfo | null>(null);
  const [charsOpen, setCharsOpen] = useState(false);
  // User-editable bibliographic fields. Korean editions split into 개정증보
  // 1판/2판 with different TOCs — the model often gets one wrong, so the user
  // corrects and we re-fetch the TOC.
  const [editAuthor, setEditAuthor] = useState("");
  const [editPublisher, setEditPublisher] = useState("");
  const [editTranslator, setEditTranslator] = useState("");
  const [editEdition, setEditEdition] = useState("");
  const [refetchingToc, setRefetchingToc] = useState(false);
  const [tocSource, setTocSource] = useState<TocSource>("llm");
  // Ref mirror for async callbacks — the store upgrade resolves seconds
  // after launch and must see the LATEST source, not a stale closure.
  const tocSourceRef = useRef<TocSource>("llm");
  const [tocShooting, setTocShooting] = useState(false);
  const [session, setSession] = useState<Session | null>(null);
  const [creating, setCreating] = useState(false);
  const [identifying, setIdentifying] = useState(false);
  const [coverEvidence, setCoverEvidence] = useState<string>("");
  const [error, setError] = useState<string | null>(null);
  const [detecting, setDetecting] = useState(false);
  // 자동 인식 기본 ON — 이 앱의 핵심 동작이 "종이책을 계속 인식해서 챕터
  // 음악을 자동으로 바꾸는 것"인데, 기본 OFF면 세션이 영원히 1장에 머물러
  // "왜 안 바뀌지?"가 된다. 세션이 생기면 즉시 1회 인식 + 10초 주기.
  const [auto, setAuto] = useState(true);
  const [musicOn, setMusicOn] = useState(false);
  const [audioStatusLog, setAudioStatusLog] = useState<string>("");
  const [volume, setVolume] = useState(0.8);
  const [camMode, setCamMode] = useState<CamMode>(() => {
    // Default to phone camera if the browser likely has one — laptops always
    // do; only fall back to board mode if explicitly chosen later.
    return "phone";
  });
  const [camStreamOn, setCamStreamOn] = useState(false);
  const [camStreamError, setCamStreamError] = useState<string | null>(null);
  const [videoDevices, setVideoDevices] = useState<MediaDeviceInfo[]>([]);
  const [selectedDeviceId, setSelectedDeviceId] = useState<string>("");
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const streamRef = useRef<MediaStream | null>(null);

  const wsRef = useRef<WebSocket | null>(null);
  const ctxRef = useRef<AudioContext | null>(null);
  const gainRef = useRef<GainNode | null>(null);
  const nextStartRef = useRef(0);
  const pollRef = useRef<number | null>(null);
  const autoTimerRef = useRef<number | null>(null);

  function teardownAudio() {
    if (wsRef.current) {
      try {
        wsRef.current.close();
      } catch {
        /* */
      }
      wsRef.current = null;
    }
    if (ctxRef.current) {
      ctxRef.current.close().catch(() => {});
      ctxRef.current = null;
    }
    gainRef.current = null;
    nextStartRef.current = 0;
    setMusicOn(false);
  }

  useEffect(() => {
    return () => {
      teardownAudio();
      stopPhoneStream();
      if (pollRef.current) clearInterval(pollRef.current);
      if (autoTimerRef.current) clearInterval(autoTimerRef.current);
    };
  }, []);

  async function refreshDeviceList() {
    try {
      if (!navigator.mediaDevices?.enumerateDevices) return;
      const all = await navigator.mediaDevices.enumerateDevices();
      const cams = all.filter((d) => d.kind === "videoinput");
      setVideoDevices(cams);
      if (cams.length && !selectedDeviceId) {
        setSelectedDeviceId(cams[0].deviceId);
      }
    } catch {
      /* */
    }
  }

  async function startPhoneStream(deviceId?: string) {
    setCamStreamError(null);
    // Stop any prior stream so we can switch devices cleanly.
    if (streamRef.current) {
      streamRef.current.getTracks().forEach((t) => t.stop());
      streamRef.current = null;
    }
    try {
      // Build a constraint that works on phones (back camera preferred) AND
      // laptops/desktops (any webcam). Try the user's chosen device first;
      // if that fails, fall back to environment-facing; if THAT fails, fall
      // back to any camera at all.
      const attempts: MediaStreamConstraints[] = [];
      if (deviceId) {
        attempts.push({
          video: { deviceId: { exact: deviceId }, width: { ideal: 1280 }, height: { ideal: 720 } },
          audio: false,
        });
      }
      attempts.push({
        video: { facingMode: { ideal: "environment" }, width: { ideal: 1280 }, height: { ideal: 720 } },
        audio: false,
      });
      attempts.push({ video: true, audio: false });

      let stream: MediaStream | null = null;
      let lastErr: any = null;
      for (const c of attempts) {
        try {
          stream = await navigator.mediaDevices.getUserMedia(c);
          break;
        } catch (e) {
          lastErr = e;
        }
      }
      if (!stream) throw lastErr ?? new Error("카메라를 열 수 없어요");

      streamRef.current = stream;
      if (videoRef.current) {
        videoRef.current.srcObject = stream;
        await videoRef.current.play().catch(() => {});
      }
      setCamStreamOn(true);
      // Now that we have permission, labels are populated — refresh the list.
      refreshDeviceList();
      // Remember which device we actually got.
      const track = stream.getVideoTracks()[0];
      const settings = track?.getSettings?.();
      if (settings?.deviceId && settings.deviceId !== selectedDeviceId) {
        setSelectedDeviceId(settings.deviceId);
      }
    } catch (e: any) {
      const msg = String(e?.message ?? e);
      setCamStreamError(
        msg.includes("Permission") || e?.name === "NotAllowedError"
          ? "카메라 권한이 필요해요. 주소창 옆 카메라 아이콘에서 허용해주세요."
          : e?.name === "NotFoundError"
            ? "사용 가능한 카메라가 없어요. 노트북 웹캠/외장 USB 카메라가 연결돼있는지 확인해주세요."
            : `카메라를 열 수 없어요: ${msg}`,
      );
    }
  }

  function stopPhoneStream() {
    const s = streamRef.current;
    if (s) {
      s.getTracks().forEach((t) => t.stop());
    }
    streamRef.current = null;
    if (videoRef.current) {
      videoRef.current.srcObject = null;
    }
    setCamStreamOn(false);
  }

  useEffect(() => {
    if (camMode === "phone") {
      startPhoneStream(selectedDeviceId || undefined);
    } else {
      stopPhoneStream();
    }
  }, [camMode]);

  // When user picks a different device from the dropdown, restart the stream.
  useEffect(() => {
    if (camMode !== "phone" || !selectedDeviceId || !camStreamOn) return;
    const current = streamRef.current?.getVideoTracks()[0]?.getSettings?.()?.deviceId;
    if (current === selectedDeviceId) return;
    startPhoneStream(selectedDeviceId);
  }, [selectedDeviceId]);

  // Watch for plug-in/plug-out of webcams so the dropdown stays accurate.
  useEffect(() => {
    if (!navigator.mediaDevices?.addEventListener) return;
    const onChange = () => refreshDeviceList();
    navigator.mediaDevices.addEventListener("devicechange", onChange);
    refreshDeviceList();
    return () => navigator.mediaDevices.removeEventListener("devicechange", onChange);
  }, []);

  async function capturePhoneFrame(): Promise<Blob> {
    const v = videoRef.current;
    if (!v || !camStreamOn) {
      throw new Error("폰 카메라가 켜져있지 않아요. 권한을 확인하세요.");
    }
    // Make sure the video has at least one frame ready.
    if (v.readyState < 2) {
      await new Promise<void>((res) => {
        const onReady = () => {
          v.removeEventListener("loadeddata", onReady);
          res();
        };
        v.addEventListener("loadeddata", onReady);
        setTimeout(res, 1500);
      });
    }
    // Cap the long side at 1024px and quality at 0.72. Gemini Vision
    // downsamples larger images anyway — uploading a 1280×720 raw frame at
    // 0.85 quality costs ~140KB and adds ~1-2s on slow networks; the
    // downscaled version is ~40KB and reads the same off a book cover.
    const MAX_DIM = 1024;
    const srcW = v.videoWidth || 1280;
    const srcH = v.videoHeight || 720;
    const scale = Math.min(1, MAX_DIM / Math.max(srcW, srcH));
    const w = Math.round(srcW * scale);
    const h = Math.round(srcH * scale);
    const canvas = document.createElement("canvas");
    canvas.width = w;
    canvas.height = h;
    const ctx = canvas.getContext("2d");
    if (!ctx) throw new Error("Canvas context 생성 실패");
    ctx.drawImage(v, 0, 0, w, h);
    return new Promise<Blob>((resolve, reject) => {
      canvas.toBlob(
        (b) => (b ? resolve(b) : reject(new Error("이미지 인코딩 실패"))),
        "image/jpeg",
        0.72,
      );
    });
  }

  useEffect(() => {
    if (gainRef.current) {
      try {
        gainRef.current.gain.value = volume;
      } catch {
        /* */
      }
    }
  }, [volume]);

  async function refreshSession(id: string) {
    try {
      const res = await fetch(`/camera/sessions/${id}`);
      if (res.status === 404) {
        // Camera sessions live in backend memory — a server restart wipes
        // them. Stop the dead-session loops and drop back to the setup
        // screen, but KEEP the book fields and locked TOC so one click on
        // 시작하기 rebuilds the session without re-scanning the cover.
        teardownAudio();
        if (pollRef.current) clearInterval(pollRef.current);
        if (autoTimerRef.current) clearInterval(autoTimerRef.current);
        pollRef.current = null;
        autoTimerRef.current = null;
        setAuto(false);
        setSession(null);
        setError(
          "백엔드가 재시작되어 세션이 사라졌어요. 책 정보는 남아있으니 '시작하기'를 다시 눌러주세요.",
        );
        return;
      }
      if (!res.ok) return;
      const fresh: Session = await res.json();
      setSession(fresh);
    } catch {
      /* */
    }
  }

  async function identifyCover() {
    setError(null);
    setCoverEvidence("");
    setIdentifying(true);
    try {
      const fd = new FormData();
      fd.append("source", camMode);
      if (camMode === "phone") {
        const blob = await capturePhoneFrame();
        fd.append("photo", blob, "cover.jpg");
      }
      const res = await fetch("/camera/identify", { method: "POST", body: fd });
      if (!res.ok) {
        const text = await res.text();
        throw new Error(text || `표지 인식 실패 (${res.status})`);
      }
      const result: CoverInfo = await res.json();
      if (!result.title) {
        setError(
          result.evidence ||
            "표지에서 제목을 읽지 못했어요. 책 표지를 카메라에 더 가까이 비춰보세요.",
        );
        return;
      }
      // Show ONLY the title in the input (clean). Author / publisher / etc
      // ride along in `cover` state and are sent with create_session so the
      // TOC lookup is grounded in this specific edition.
      setBookName(result.title);
      setCover(result);
      markTocSource("llm");
      setEditAuthor(result.author || "");
      setEditPublisher(result.publisher || "");
      setEditTranslator(result.translator || "");
      setEditEdition(result.edition || "");
      const parts: string[] = [];
      if (result.author) parts.push(`저자: ${result.author}`);
      if (result.publisher) parts.push(`출판사: ${result.publisher}`);
      if (result.translator) parts.push(`번역: ${result.translator}`);
      if (result.edition) parts.push(`판: ${result.edition}`);
      setCoverEvidence(
        `${Math.round((result.confidence ?? 0) * 100)}% — ${parts.join(", ") || "추가 정보 없음"}${result.evidence ? ` · ${result.evidence}` : ""}`,
      );
      // Vision call gives an LLM-knowledge TOC; the bookstore page has the
      // REAL printed one. Upgrade in the background (~2-4s) — only adopted
      // when the crawler actually hits, so the TOC the user locks in is the
      // verbatim published TOC whenever one exists.
      void upgradeTocFromStore(
        result.title,
        result.author || "",
        result.publisher || "",
        result.translator || "",
        result.edition || "",
        result.isbn || "",
      );
    } catch (e: any) {
      setError(e?.message ?? String(e));
    } finally {
      setIdentifying(false);
    }
  }

  function markTocSource(s: TocSource) {
    tocSourceRef.current = s;
    setTocSource(s);
  }

  async function upgradeTocFromStore(
    name: string,
    author: string,
    publisher: string,
    translator: string,
    edition: string,
    isbn: string,
  ) {
    setRefetchingToc(true);
    try {
      const res = await fetch("/camera/toc-lookup", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          book_name: name,
          author,
          publisher,
          translator,
          edition,
          isbn,
        }),
      });
      if (!res.ok) return;
      const result = await res.json();
      if (!result.verbatim || !result.toc?.length) return;
      // The user may have photographed the actual TOC page while this
      // lookup was in flight — that TOC is ground truth, never downgrade.
      if (tocSourceRef.current === "photo") return;
      setCover((c) =>
        c
          ? {
              ...c,
              toc: result.toc,
              matched_edition: result.matched_edition ?? "",
              toc_error: "",
            }
          : c,
      );
      markTocSource("store");
    } catch {
      /* crawler miss — keep the TOC from the vision call */
    } finally {
      setRefetchingToc(false);
    }
  }

  // One photographed 목차 page per call; later pages APPEND in shot order.
  // Dedup on a normalized title so overlapping shots don't duplicate rows.
  function mergeTocPages(base: Chapter[], added: Chapter[]): Chapter[] {
    const norm = (t: string) =>
      t.replace(/[\s,.·:\-'"“”‘’]+/g, "").toLowerCase();
    const seen = new Set(base.map((c) => norm(c.title)));
    const out = [...base];
    for (const c of added) {
      const k = norm(c.title);
      if (!k || seen.has(k)) continue;
      seen.add(k);
      out.push(c);
    }
    return out.map((c, i) => ({ ...c, idx: i }));
  }

  async function captureTocPage(append: boolean) {
    setError(null);
    setTocShooting(true);
    try {
      const fd = new FormData();
      fd.append("source", camMode);
      fd.append("book_name", bookName.trim());
      if (camMode === "phone") {
        const blob = await capturePhoneFrame();
        fd.append("photo", blob, "toc.jpg");
      }
      const res = await fetch("/camera/toc-from-photo", {
        method: "POST",
        body: fd,
      });
      if (!res.ok) {
        const text = await res.text();
        throw new Error(text || `목차 페이지 판독 실패 (${res.status})`);
      }
      const result = await res.json();
      if (result.toc_error || !result.toc?.length) {
        setError(
          result.toc_error ||
            "목차를 읽지 못했어요. 목차 페이지가 잘 보이게 다시 찍어주세요.",
        );
        return;
      }
      const base =
        append && tocSourceRef.current === "photo" ? (cover?.toc ?? []) : [];
      const merged = mergeTocPages(base, result.toc);
      const matched = `책에서 직접 찍은 목차 (${merged.length}개 항목)`;
      setCover((c) =>
        c
          ? { ...c, toc: merged, matched_edition: matched, toc_error: "" }
          : {
              title: bookName.trim(),
              author: editAuthor,
              publisher: editPublisher,
              translator: editTranslator,
              edition: editEdition,
              confidence: 0,
              evidence: "",
              toc: merged,
              matched_edition: matched,
              toc_error: "",
            },
      );
      markTocSource("photo");
    } catch (e: any) {
      setError(e?.message ?? String(e));
    } finally {
      setTocShooting(false);
    }
  }

  async function refetchToc() {
    const name = bookName.trim();
    if (!name) return;
    setError(null);
    setRefetchingToc(true);
    try {
      const res = await fetch("/camera/toc-lookup", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          book_name: name,
          author: editAuthor,
          publisher: editPublisher,
          translator: editTranslator,
          edition: editEdition,
          isbn: cover?.isbn ?? "",
        }),
      });
      if (!res.ok) {
        const text = await res.text();
        throw new Error(text || `목차 가져오기 실패 (${res.status})`);
      }
      const result = await res.json();
      setCover((c) =>
        c
          ? {
              ...c,
              author: editAuthor,
              publisher: editPublisher,
              translator: editTranslator,
              edition: editEdition,
              toc: result.toc ?? [],
              matched_edition: result.matched_edition ?? "",
              toc_error: result.toc_error ?? "",
            }
          : c,
      );
      markTocSource(result.verbatim && result.toc?.length ? "store" : "llm");
    } catch (e: any) {
      setError(e?.message ?? String(e));
    } finally {
      setRefetchingToc(false);
    }
  }

  async function createSession() {
    const name = bookName.trim();
    if (!name) {
      setError("책 이름을 적어주세요");
      return;
    }
    setError(null);
    setCreating(true);
    try {
      // Lock in whatever TOC the user already saw (from cover identification
      // or from the manual re-fetch). Backend will use this verbatim and skip
      // a fresh LLM call — that way the user doesn't see one TOC during
      // browsing and a DIFFERENT one once music starts.
      const lockedToc = (cover?.toc ?? []).map((c) => ({
        idx: c.idx,
        title: c.title,
        summary: c.summary ?? "",
        music_prompt: (c as any).music_prompt ?? "",
        bpm: c.bpm ?? 80,
        mood: c.mood ?? "",
      }));
      // Best-effort location → ~10% music tint (place/weather/season). Skipped
      // silently if the user denies permission.
      const geo = await getGeo();
      const res = await fetch("/camera/sessions", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          book_name: name,
          author: editAuthor,
          publisher: editPublisher,
          translator: editTranslator,
          edition: editEdition,
          toc: lockedToc.length > 0 ? lockedToc : null,
          matched_edition: cover?.matched_edition ?? "",
          ...(geo ? { lat: geo.lat, lon: geo.lon } : {}),
        }),
      });
      if (!res.ok) {
        const text = await res.text();
        throw new Error(text || `세션 생성 실패 (${res.status})`);
      }
      const s: Session = await res.json();
      setSession(s);
      // Poll for audio readiness while generating.
      if (pollRef.current) clearInterval(pollRef.current);
      pollRef.current = window.setInterval(() => {
        refreshSession(s.id);
      }, 4000);
    } catch (e: any) {
      setError(e?.message ?? String(e));
    } finally {
      setCreating(false);
    }
  }

  // Stop polling once audio is fully ready (or failed).
  useEffect(() => {
    if (!session) return;
    if (
      pollRef.current &&
      (session.audio_status === "ready" || session.audio_status === "failed")
    ) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  }, [session?.audio_status]);

  function startMusic(sessionId: string) {
    if (musicOn) return;
    setError(null);
    const ctx = new AudioContext({ sampleRate: SAMPLE_RATE });
    const gain = ctx.createGain();
    gain.gain.value = volume;
    gain.connect(ctx.destination);
    ctxRef.current = ctx;
    gainRef.current = gain;
    nextStartRef.current = ctx.currentTime + 0.15;

    const scheme = location.protocol === "https:" ? "wss" : "ws";
    const u = loadUser();
    const params = new URLSearchParams();
    if (u?.idToken) params.set("token", u.idToken);
    params.set("cid", getClientId());
    const ws = new WebSocket(
      `${scheme}://${location.host}/ws/camera/${sessionId}?${params.toString()}`,
    );
    ws.binaryType = "arraybuffer";

    ws.onopen = () => {
      setMusicOn(true);
    };
    ws.onerror = () => {
      setError("WebSocket 연결 오류 — 백엔드가 실행 중인지 확인해주세요.");
      teardownAudio();
    };
    ws.onclose = (e) => {
      if (e.code === 1012) {
        // Service-restart close — session polling will notice the 404 and
        // guide the user back to 시작하기.
        setError("백엔드가 재시작됐어요. 세션을 다시 시작해주세요.");
      } else if (e.code !== 1000) {
        setError(`백엔드가 연결을 닫았어요 (code ${e.code})`);
      }
      setMusicOn(false);
    };
    ws.onmessage = (ev) => {
      if (typeof ev.data === "string") {
        try {
          const msg = JSON.parse(ev.data);
          if (msg.type === "waiting") {
            setAudioStatusLog(
              `챕터 ${msg.chapter_idx + 1} 음악 생성 중... (${msg.ready_segments?.length ?? 0} 준비됨)`,
            );
          } else if (msg.type === "chapter") {
            setAudioStatusLog("");
          }
        } catch {
          /* */
        }
        return;
      }
      const pcm = new Int16Array(ev.data as ArrayBuffer);
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
      src.connect(gain);
      const startAt = Math.max(ctx.currentTime, nextStartRef.current);
      src.start(startAt);
      nextStartRef.current = startAt + buf.duration;
    };

    wsRef.current = ws;
  }

  async function detectOnce() {
    if (!session) return;
    setDetecting(true);
    setError(null);
    try {
      const fd = new FormData();
      fd.append("source", camMode);
      if (camMode === "phone") {
        const blob = await capturePhoneFrame();
        fd.append("photo", blob, "page.jpg");
      }
      const res = await fetch(`/camera/sessions/${session.id}/detect`, {
        method: "POST",
        body: fd,
      });
      if (!res.ok) {
        const text = await res.text();
        throw new Error(text || `감지 실패 (${res.status})`);
      }
      const result = await res.json();
      setSession((s) =>
        s
          ? {
              ...s,
              current_chapter_idx: result.current_chapter_idx,
              audio_status: result.audio_status,
              ready_segments: result.ready_segments ?? s.ready_segments,
              last_detection: {
                chapter_idx: result.chapter_idx,
                confidence: result.confidence,
                evidence: result.evidence,
              },
            }
          : s,
      );
    } catch (e: any) {
      setError(e?.message ?? String(e));
    } finally {
      setDetecting(false);
    }
  }

  // Auto-detect every 10s while toggled on — fast enough to catch chapter
  // turns shortly after they happen but slow enough to give Gemini's vision
  // call time to complete (~3-5s on flash-lite + headroom for the camera).
  useEffect(() => {
    if (autoTimerRef.current) {
      clearInterval(autoTimerRef.current);
      autoTimerRef.current = null;
    }
    if (auto && session) {
      autoTimerRef.current = window.setInterval(() => {
        detectOnce();
      }, 10000);
      detectOnce();
    }
  }, [auto, session?.id]);

  async function setChapter(idx: number) {
    if (!session) return;
    try {
      const res = await fetch(`/camera/sessions/${session.id}/chapter`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ chapter_idx: idx }),
      });
      if (!res.ok) return;
      const fresh: Session = await res.json();
      setSession(fresh);
    } catch {
      /* */
    }
  }

  function resetSession() {
    teardownAudio();
    if (pollRef.current) clearInterval(pollRef.current);
    if (autoTimerRef.current) clearInterval(autoTimerRef.current);
    pollRef.current = null;
    autoTimerRef.current = null;
    setSession(null);
    setBookName("");
    setCover(null);
    setEditAuthor("");
    setEditPublisher("");
    setEditTranslator("");
    setEditEdition("");
    setError(null);
    setAuto(false);
    setAudioStatusLog("");
    setCoverEvidence("");
    setCharsOpen(false);
  }

  if (!session) {
    return (
      <section className="cam-page">
        <header className="cam-header">
          <button className="cam-back" onClick={onBack}>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <line x1="19" y1="12" x2="5" y2="12" />
              <polyline points="12 19 5 12 12 5" />
            </svg>
            돌아가기
          </button>
          <h1 className="cam-title">카메라 리더</h1>
          <span className="cam-spacer" />
        </header>

        <div className="cam-intro">
          <div className="cam-eyebrow">camera reader</div>
          <h2 className="cam-h">
            <em>책 이름</em>만 적으면 — 카메라가 챕터를 알아채고 음악이 바뀝니다
          </h2>
          <p className="cam-sub">
            1. 읽고 싶은 책의 이름을 적습니다.<br />
            2. AI가 목차를 만들고 챕터마다 Lyria 음악을 만듭니다.<br />
            3. 폰·노트북·PC 웹캠 또는 XIAO 보드 카메라로 페이지를 비추면<br />
            &nbsp;&nbsp;&nbsp;어느 챕터인지 인식해서 그 챕터의 음악으로 자동 전환돼요.
          </p>

          <div className="cam-mode-row">
            <span className="cam-mode-label">카메라 소스</span>
            <div className="cam-mode-tabs">
              <button
                type="button"
                className={`cam-mode-tab ${camMode === "phone" ? "cam-mode-tab-on" : ""}`}
                onClick={() => setCamMode("phone")}
              >
                내 카메라 (폰/노트북/PC)
              </button>
              <button
                type="button"
                className={`cam-mode-tab ${camMode === "board" ? "cam-mode-tab-on" : ""}`}
                onClick={() => setCamMode("board")}
              >
                XIAO 보드
              </button>
            </div>
          </div>

          {camMode === "phone" && videoDevices.length > 1 && (
            <div className="cam-device-row">
              <span className="cam-device-label">카메라 선택</span>
              <select
                className="cam-device-select"
                value={selectedDeviceId}
                onChange={(e) => setSelectedDeviceId(e.target.value)}
              >
                {videoDevices.map((d, i) => (
                  <option key={d.deviceId || i} value={d.deviceId}>
                    {d.label || `카메라 ${i + 1}`}
                  </option>
                ))}
              </select>
            </div>
          )}

          {camMode === "phone" && (
            <div className="cam-preview">
              <video
                ref={videoRef}
                className="cam-preview-video"
                playsInline
                muted
                autoPlay
              />
              {!camStreamOn && !camStreamError && (
                <div className="cam-preview-hint">카메라 권한 요청 중…</div>
              )}
              {camStreamError && (
                <div className="cam-preview-hint cam-preview-hint-err">
                  {camStreamError}
                  <button
                    className="cam-btn"
                    style={{ marginTop: 10 }}
                    onClick={() => startPhoneStream(selectedDeviceId || undefined)}
                  >
                    다시 시도
                  </button>
                </div>
              )}
            </div>
          )}

          <div className="cam-form">
            <input
              className="cam-input"
              type="text"
              placeholder="예: 어린 왕자 / 데미안 / 1984"
              value={bookName}
              onChange={(e) => setBookName(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && !creating) createSession();
              }}
              disabled={creating || identifying}
              autoFocus
            />
            <button
              className="cam-btn"
              onClick={identifyCover}
              disabled={creating || identifying}
              title="카메라로 책 표지를 찍어서 자동으로 제목 채우기"
            >
              {identifying ? (
                <>
                  <span className="spinner-sm" /> 표지 읽는 중…
                </>
              ) : (
                <>
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z" />
                    <circle cx="12" cy="13" r="4" />
                  </svg>
                  표지 찍기
                </>
              )}
            </button>
            <button
              className="cam-btn"
              onClick={() => captureTocPage(false)}
              disabled={creating || identifying || tocShooting}
              title="책의 목차 페이지를 펼쳐서 찍으면 인쇄된 목차를 그대로 읽어옵니다 — 가장 정확해요"
            >
              {tocShooting ? (
                <>
                  <span className="spinner-sm" /> 목차 읽는 중…
                </>
              ) : (
                <>
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z" />
                    <path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z" />
                  </svg>
                  목차 찍기
                </>
              )}
            </button>
            <button
              className="cam-cta"
              onClick={createSession}
              disabled={creating || identifying || !bookName.trim()}
            >
              {creating ? (
                <>
                  <span className="spinner-sm" /> 목차 만드는 중…
                </>
              ) : (
                "시작하기"
              )}
            </button>
          </div>

          {cover && (
            <div className="cam-bib">
              <div className="cam-bib-head">
                <span>표지에서 읽음 ({Math.round((cover.confidence ?? 0) * 100)}%)</span>
                <span className="cam-bib-hint">필요하면 직접 고치고 "목차 다시 가져오기"</span>
              </div>
              <div className="cam-bib-grid">
                <label className="cam-bib-field">
                  <span>저자</span>
                  <input
                    type="text"
                    value={editAuthor}
                    onChange={(e) => setEditAuthor(e.target.value)}
                    placeholder="원저자"
                  />
                </label>
                <label className="cam-bib-field">
                  <span>출판사</span>
                  <input
                    type="text"
                    value={editPublisher}
                    onChange={(e) => setEditPublisher(e.target.value)}
                    placeholder="민음사 / 문학동네 / Penguin …"
                  />
                </label>
                <label className="cam-bib-field">
                  <span>번역가</span>
                  <input
                    type="text"
                    value={editTranslator}
                    onChange={(e) => setEditTranslator(e.target.value)}
                    placeholder="해당 없으면 비워두세요"
                  />
                </label>
                <label className="cam-bib-field">
                  <span>판 (개정 증보 1판/2판 등)</span>
                  <input
                    type="text"
                    value={editEdition}
                    onChange={(e) => setEditEdition(e.target.value)}
                    placeholder="예: 개정 증보 2판 3쇄"
                  />
                </label>
              </div>
              <div className="cam-bib-actions">
                <button
                  type="button"
                  className="cam-btn"
                  onClick={refetchToc}
                  disabled={refetchingToc || !bookName.trim()}
                >
                  {refetchingToc ? (
                    <>
                      <span className="spinner-sm" /> 목차 가져오는 중…
                    </>
                  ) : (
                    "이 정보로 목차 다시 가져오기"
                  )}
                </button>
                {cover.evidence && (
                  <span className="cam-bib-evidence">"{cover.evidence}"</span>
                )}
              </div>
            </div>
          )}

          {cover?.toc && cover.toc.length > 0 && (
            <div className="cam-toc-preview">
              <div className="cam-toc-preview-head">
                목차 ({cover.toc.length}장)
                {cover.matched_edition && (
                  <span className="cam-toc-preview-edition">
                    · {cover.matched_edition}
                  </span>
                )}
              </div>
              <ol className="cam-toc-preview-list">
                {cover.toc.map((c) => (
                  <li key={c.idx}>
                    <span className="cam-toc-preview-idx">
                      {String(c.idx + 1).padStart(2, "0")}
                    </span>
                    <span className="cam-toc-preview-title">{c.title}</span>
                  </li>
                ))}
              </ol>
              {tocSource === "photo" && (
                <div className="cam-bib-actions" style={{ marginTop: 8 }}>
                  <button
                    type="button"
                    className="cam-btn"
                    onClick={() => captureTocPage(true)}
                    disabled={tocShooting || creating}
                  >
                    {tocShooting ? (
                      <>
                        <span className="spinner-sm" /> 목차 읽는 중…
                      </>
                    ) : (
                      "목차 다음 페이지 찍기"
                    )}
                  </button>
                  <span className="cam-bib-hint">
                    목차가 여러 페이지면 순서대로 이어서 찍어주세요
                  </span>
                </div>
              )}
            </div>
          )}

          {cover?.toc_error && (
            <div className="cam-detect-card" style={{ marginTop: 12 }}>
              <div className="cam-detect-label">목차 가져오기 실패</div>
              <div className="cam-detect-evidence">{cover.toc_error}</div>
            </div>
          )}

          {error && <div className="cam-error">{error}</div>}

          <div className="cam-tip">
            <strong>준비물:</strong> XIAO ESP32-S3 + 카메라 모듈, Node 서버
            (기본 <code>http://192.168.0.188:4000</code>).<br />
            보드는 <code>/trigger</code>를 0.7초마다 폴링하고, '1'을 받으면
            한 장 찍어서 업로드합니다. 다른 주소로 운영 중이면 백엔드 환경변수
            <code>CAMERA_SERVER_URL</code>로 바꿔주세요.
          </div>
        </div>
      </section>
    );
  }

  const current = session.toc[session.current_chapter_idx];
  const lastDet = session.last_detection;

  return (
    <section className="cam-page">
      <header className="cam-header">
        <button className="cam-back" onClick={resetSession}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <line x1="19" y1="12" x2="5" y2="12" />
            <polyline points="12 19 5 12 12 5" />
          </svg>
          다른 책
        </button>
        <div style={{ flex: 1, textAlign: "center" }}>
          <h1 className="cam-title" style={{ marginBottom: 2 }}>{session.book_name}</h1>
          {(session.author || session.publisher) && (
            <div style={{ fontSize: 11.5, color: "rgba(255,255,255,0.5)" }}>
              {[session.author, session.publisher].filter(Boolean).join(" · ")}
            </div>
          )}
        </div>
        <button className="cam-back" onClick={() => setCharsOpen(true)}>
          인물
        </button>
        <button className="cam-back" onClick={onBack}>
          홈
        </button>
      </header>

      <CharactersPanel
        bookId={session.id}
        endpoint={`/camera/sessions/${session.id}/characters`}
        open={charsOpen}
        onClose={() => setCharsOpen(false)}
      />

      {session.matched_edition && (
        <div className="cam-detect-card" style={{ marginBottom: 16 }}>
          <div className="cam-detect-label">AI가 본 판본</div>
          <div className="cam-detect-evidence">{session.matched_edition}</div>
        </div>
      )}

      {/* Camera source picker — same control as on the landing form */}
      <div className="cam-mode-row" style={{ marginBottom: 14 }}>
        <span className="cam-mode-label">카메라 소스</span>
        <div className="cam-mode-tabs">
          <button
            type="button"
            className={`cam-mode-tab ${camMode === "phone" ? "cam-mode-tab-on" : ""}`}
            onClick={() => setCamMode("phone")}
          >
            내 카메라
          </button>
          <button
            type="button"
            className={`cam-mode-tab ${camMode === "board" ? "cam-mode-tab-on" : ""}`}
            onClick={() => setCamMode("board")}
          >
            XIAO 보드
          </button>
        </div>
      </div>

      {camMode === "phone" && videoDevices.length > 1 && (
        <div className="cam-device-row" style={{ marginBottom: 12 }}>
          <span className="cam-device-label">카메라 선택</span>
          <select
            className="cam-device-select"
            value={selectedDeviceId}
            onChange={(e) => setSelectedDeviceId(e.target.value)}
          >
            {videoDevices.map((d, i) => (
              <option key={d.deviceId || i} value={d.deviceId}>
                {d.label || `카메라 ${i + 1}`}
              </option>
            ))}
          </select>
        </div>
      )}

      {camMode === "phone" && (
        <div className="cam-preview cam-preview-sm">
          <video
            ref={videoRef}
            className="cam-preview-video"
            playsInline
            muted
            autoPlay
          />
          {!camStreamOn && !camStreamError && (
            <div className="cam-preview-hint">카메라 켜는 중…</div>
          )}
          {camStreamError && (
            <div className="cam-preview-hint cam-preview-hint-err">
              {camStreamError}
            </div>
          )}
        </div>
      )}

      <div className="cam-status-row">
        <div
          className={`cam-pill cam-pill-${session.audio_status}`}
          title={`Lyria 음악 ${session.ready_segments.length}/${session.toc.length} 완료`}
        >
          {session.audio_status === "pending" && "음악 준비 중"}
          {session.audio_status === "generating" &&
            `음악 생성 ${session.ready_segments.length}/${session.toc.length}`}
          {session.audio_status === "ready" && `음악 ${session.toc.length}챕터 준비 완료`}
          {session.audio_status === "failed" &&
            `일부 실패 (${session.ready_segments.length}/${session.toc.length})`}
        </div>

        <button
          className={`cam-btn ${musicOn ? "cam-btn-on" : ""}`}
          onClick={() => (musicOn ? teardownAudio() : startMusic(session.id))}
          disabled={session.ready_segments.length === 0}
          title={
            session.ready_segments.length === 0
              ? "첫 챕터 음악이 준비되면 활성화됩니다"
              : ""
          }
        >
          {musicOn ? "음악 정지" : "음악 시작"}
        </button>

        <button
          className={`cam-btn ${detecting ? "cam-btn-busy" : ""}`}
          onClick={detectOnce}
          disabled={detecting}
        >
          {detecting ? (
            <>
              <span className="spinner-sm" /> 인식 중…
            </>
          ) : (
            "지금 인식"
          )}
        </button>

        <label className="cam-toggle">
          <input
            type="checkbox"
            checked={auto}
            onChange={(e) => setAuto(e.target.checked)}
          />
          <span>10초마다 자동 인식</span>
        </label>

        <label className="cam-vol">
          <span>음량</span>
          <input
            type="range"
            min={0}
            max={1}
            step={0.05}
            value={volume}
            onChange={(e) => setVolume(parseFloat(e.target.value))}
          />
        </label>
      </div>

      {(error || audioStatusLog) && (
        <div className="cam-error">
          {error ?? audioStatusLog}
        </div>
      )}

      {current && (
        <div className="cam-now">
          <div className="cam-now-label">지금 재생 중</div>
          <div className="cam-now-title">
            <span className="cam-chip">CH {current.idx + 1}</span>
            {current.title}
          </div>
          <div className="cam-now-meta">
            <span>{current.mood || "—"}</span>
            <span>{current.bpm} BPM</span>
            {session.ready_segments.includes(current.idx) ? (
              <span className="cam-meta-ok">✓ 음악 준비됨</span>
            ) : (
              <span className="cam-meta-wait">음악 생성 중…</span>
            )}
          </div>
          {current.summary && <p className="cam-now-summary">{current.summary}</p>}
        </div>
      )}

      {lastDet && (
        <div className="cam-detect-card">
          <div className="cam-detect-label">
            마지막 인식 — 신뢰도 {Math.round(lastDet.confidence * 100)}%
          </div>
          <div className="cam-detect-text">
            {lastDet.chapter_idx >= 0
              ? `→ "${session.toc[lastDet.chapter_idx]?.title}"`
              : "→ 챕터를 알아내지 못했어요"}
          </div>
          {lastDet.evidence && (
            <div className="cam-detect-evidence">"{lastDet.evidence}"</div>
          )}
        </div>
      )}

      <div className="cam-toc">
        <div className="cam-toc-head">목차</div>
        {session.toc.map((c) => {
          const isCurrent = c.idx === session.current_chapter_idx;
          const isReady = session.ready_segments.includes(c.idx);
          return (
            <button
              key={c.idx}
              className={`cam-toc-row ${isCurrent ? "cam-toc-row-active" : ""}`}
              onClick={() => setChapter(c.idx)}
            >
              <span className="cam-toc-idx">
                {String(c.idx + 1).padStart(2, "0")}
              </span>
              <span className="cam-toc-body">
                <span className="cam-toc-title">{c.title}</span>
                {c.summary && <span className="cam-toc-summary">{c.summary}</span>}
              </span>
              <span className="cam-toc-side">
                <span className="cam-toc-mood">{c.mood}</span>
                <span className="cam-toc-bpm">{c.bpm}</span>
                <span
                  className={isReady ? "cam-dot cam-dot-ok" : "cam-dot"}
                  aria-label={isReady ? "음악 준비됨" : "음악 생성 중"}
                />
              </span>
            </button>
          );
        })}
      </div>

      <div className="cam-footer">
        카메라 서버: <code>{session.camera_server}</code>
      </div>
    </section>
  );
}
