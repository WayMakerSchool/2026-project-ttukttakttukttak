import { useEffect, useState } from "react";
import { authHeaders, loadUser } from "../lib/auth";
import { authQuery, progressHeaders } from "../lib/client-id";

function idHeaders(): Record<string, string> {
  return { ...progressHeaders(), ...authHeaders(loadUser()) };
}

interface Props {
  bookId: string;
  page: number;
  open: boolean;
  onClose: () => void;
}

type State = "checking" | "idle" | "loading" | "ready" | "error";

/** Floating modal that shows / generates an illustration for the chapter
 *  containing `page`. Calls POST on first generate, GETs the cached image
 *  on re-opens so it's free after the first call. */
export function ChapterImageModal({ bookId, page, open, onClose }: Props) {
  const [state, setState] = useState<State>("checking");
  const [err, setErr] = useState<string | null>(null);
  const [ver, setVer] = useState(0);

  // On open, ask the backend whether the CURRENT chapter has a cached image
  // (the GET endpoint already resolves `page` to the chapter start, so a
  // HEAD-style probe tells us cleanly whether we're idle or ready).
  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    setState("checking");
    setErr(null);

    (async () => {
      try {
        const r = await fetch(`/books/${bookId}/chapters/${page}/image`, {
          method: "HEAD",
          headers: idHeaders(),
        });
        if (cancelled) return;
        if (r.ok) {
          setVer((v) => v + 1);
          setState("ready");
        } else {
          setState("idle");
        }
      } catch {
        if (!cancelled) setState("idle");
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [open, bookId, page]);

  useEffect(() => {
    if (!open) return;
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  async function generate() {
    setState("loading");
    setErr(null);
    try {
      const r = await fetch(`/books/${bookId}/chapters/${page}/image`, {
        method: "POST",
        headers: idHeaders(),
      });
      if (!r.ok) {
        const t = await r.text();
        throw new Error(t || `HTTP ${r.status}`);
      }
      setVer((v) => v + 1);
      setState("ready");
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
      setState("error");
    }
  }

  if (!open) return null;

  return (
    <>
      <div className="img-backdrop" onClick={onClose} aria-hidden="true" />
      <div className="img-modal" role="dialog" aria-label="장 사진">
        <header className="img-modal-head">
          <h3>이 장의 사진</h3>
          <button className="img-close" onClick={onClose} aria-label="닫기" type="button">
            ×
          </button>
        </header>

        <div className="img-modal-body">
          {state === "checking" && (
            <div className="img-empty">
              <div className="spinner" />
              <span>확인 중…</span>
            </div>
          )}

          {state === "idle" && (
            <div className="img-empty">
              <p className="img-hint">
                현재 페이지가 속한 챕터의 분위기·등장인물·배경을 AI가 한 장면으로 그려줍니다.
              </p>
              <button className="img-primary-btn" onClick={generate} type="button">
                사진 만들기
              </button>
              <p className="img-cost">생성 시간 약 5~15초 · 1회 약 $0.03</p>
            </div>
          )}

          {state === "loading" && (
            <div className="img-empty">
              <div className="spinner" />
              <span>그리는 중… (10초 정도 걸려요)</span>
            </div>
          )}

          {state === "ready" && (
            <>
              <img
                className="img-result"
                src={`/books/${bookId}/chapters/${page}/image${authQuery(loadUser()?.idToken)}&v=${ver}`}
                alt="장 사진"
              />
              <div className="img-actions">
                <button className="img-secondary-btn" onClick={generate} type="button">
                  다시 그리기
                </button>
              </div>
            </>
          )}

          {state === "error" && (
            <div className="img-empty">
              <p className="img-error">생성 실패: {err}</p>
              <button className="img-primary-btn" onClick={generate} type="button">
                다시 시도
              </button>
            </div>
          )}
        </div>
      </div>
    </>
  );
}
