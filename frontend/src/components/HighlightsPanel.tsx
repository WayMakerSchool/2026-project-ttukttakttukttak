import { useEffect, useState } from "react";
import { authHeaders, type AuthUser } from "../lib/auth";
import { progressHeaders } from "../lib/client-id";

export interface Highlight {
  id: number;
  page: number;
  text: string;
  note: string | null;
  color: string | null;
  created_at: string;
}

interface Props {
  bookId: string;
  open: boolean;
  refreshKey: number;
  user: AuthUser | null;
  onClose: () => void;
  onJump: (page: number) => void;
}

function formatDate(iso: string): string {
  try {
    return new Date(iso).toLocaleDateString("ko-KR", {
      month: "short",
      day: "numeric",
    });
  } catch {
    return "";
  }
}

export function HighlightsPanel({ bookId, open, refreshKey, user, onClose, onJump }: Props) {
  const [items, setItems] = useState<Highlight[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!open || !bookId) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    fetch(`/books/${bookId}/highlights`, {
      headers: { ...progressHeaders(), ...authHeaders(user) },
    })
      .then((r) => {
        if (!r.ok) throw new Error(`status ${r.status}`);
        return r.json();
      })
      .then((data) => {
        if (cancelled) return;
        setItems((data.highlights ?? []) as Highlight[]);
        setLoading(false);
      })
      .catch((e) => {
        if (cancelled) return;
        setError(e instanceof Error ? e.message : String(e));
        setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [bookId, open, refreshKey, user]);

  useEffect(() => {
    if (!open) return;
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  async function deleteOne(id: number) {
    try {
      const res = await fetch(`/books/${bookId}/highlights/${id}`, {
        method: "DELETE",
        headers: { ...progressHeaders(), ...authHeaders(user) },
      });
      if (res.ok || res.status === 204) {
        setItems((prev) => prev.filter((h) => h.id !== id));
      }
    } catch {
      /* swallow */
    }
  }

  if (!open) return null;

  return (
    <>
      <div className="toc-backdrop" onClick={onClose} aria-hidden="true" />
      <aside className="toc-drawer" role="dialog" aria-label="하이라이트">
        <header className="toc-header">
          <h2>하이라이트</h2>
          <button className="toc-close" onClick={onClose} aria-label="닫기" type="button">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <line x1="18" y1="6" x2="6" y2="18" />
              <line x1="6" y1="6" x2="18" y2="18" />
            </svg>
          </button>
        </header>

        <div className="toc-body">
          {loading && (
            <div className="toc-state">
              <div className="spinner" />
              <span>불러오는 중…</span>
            </div>
          )}
          {!loading && error && (
            <div className="toc-state">
              <span className="error">{error}</span>
            </div>
          )}
          {!loading && !error && items.length === 0 && (
            <div className="toc-state">
              아직 저장된 하이라이트가 없어요.
              <br />
              Novel · Comfort 모드에서 텍스트를 드래그해 저장해보세요.
            </div>
          )}
          {!loading && items.length > 0 && (
            <ul className="highlight-list">
              {items.map((h) => (
                <li key={h.id} className="highlight-item">
                  <button
                    className="highlight-main"
                    onClick={() => {
                      onJump(h.page);
                      onClose();
                    }}
                    type="button"
                  >
                    <div className="highlight-head">
                      <span className="highlight-page">p.{h.page}</span>
                      <span className="highlight-date">{formatDate(h.created_at)}</span>
                    </div>
                    <p className="highlight-text">{h.text}</p>
                    {h.note && <p className="highlight-note">{h.note}</p>}
                  </button>
                  <button
                    className="highlight-delete"
                    onClick={() => deleteOne(h.id)}
                    aria-label="하이라이트 삭제"
                    type="button"
                  >
                    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                      <line x1="18" y1="6" x2="6" y2="18" />
                      <line x1="6" y1="6" x2="18" y2="18" />
                    </svg>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      </aside>
    </>
  );
}
