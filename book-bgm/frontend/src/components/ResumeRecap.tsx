import { useEffect, useState } from "react";
import { authHeaders, type AuthUser } from "../lib/auth";
import { progressHeaders } from "../lib/client-id";

interface ChapterRecap {
  page: number;
  title: string;
  summary: string;
}

interface RecapData {
  last_page: number;
  last_read_at: string | null;
  current_chapter: ChapterRecap | null;
  previously: ChapterRecap[];
}

interface Props {
  bookId: string;
  user: AuthUser | null;
  onResume: (page: number) => void;
  onStartOver: () => void;
}

function formatHoursAgo(iso: string | null): string {
  if (!iso) return "";
  const diffMs = Date.now() - new Date(iso).getTime();
  const days = Math.floor(diffMs / (24 * 60 * 60 * 1000));
  if (days >= 7) return `${Math.floor(days / 7)}주 전`;
  if (days >= 1) return `${days}일 전`;
  const hours = Math.floor(diffMs / (60 * 60 * 1000));
  if (hours >= 1) return `${hours}시간 전`;
  return "방금 전";
}

export function ResumeRecap({ bookId, user, onResume, onStartOver }: Props) {
  const [data, setData] = useState<RecapData | null>(null);
  const [loading, setLoading] = useState(true);
  const [dismissed, setDismissed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    fetch(`/books/${bookId}/recap`, {
      headers: { ...progressHeaders(), ...authHeaders(user) },
    })
      .then((r) => (r.ok ? r.json() : null))
      .then((d) => {
        if (cancelled) return;
        setData(d);
        setLoading(false);
      })
      .catch(() => {
        if (cancelled) return;
        setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [bookId, user]);

  // Don't show anything if loading, dismissed, or no prior progress.
  if (loading || dismissed) return null;
  if (!data || data.last_page <= 1) return null;

  return (
    <>
      <div className="toc-backdrop" onClick={() => setDismissed(true)} aria-hidden="true" />
      <div className="recap-modal" role="dialog" aria-label="이어 읽기 안내">
        <div className="recap-eyebrow">previously on</div>
        <h2 className="recap-title">
          지난번엔 <em>{data.last_page}쪽</em>까지 읽으셨어요
          {data.last_read_at && (
            <span className="recap-ago"> · {formatHoursAgo(data.last_read_at)}</span>
          )}
        </h2>

        {data.current_chapter && (
          <div className="recap-current">
            지금 읽는 곳:{" "}
            <span className="recap-current-title">{data.current_chapter.title}</span>
          </div>
        )}

        {data.previously.length > 0 && (
          <div className="recap-body">
            <div className="recap-section-label">지금까지의 이야기</div>
            <ol className="recap-list">
              {data.previously.map((c, i) => (
                <li key={`${c.page}-${i}`} className="recap-item">
                  <div className="recap-item-head">
                    <span className="recap-item-title">{c.title}</span>
                    <span className="recap-item-page">p.{c.page}</span>
                  </div>
                  {c.summary && <p className="recap-item-summary">{c.summary}</p>}
                </li>
              ))}
            </ol>
          </div>
        )}

        <div className="recap-actions">
          <button
            type="button"
            className="cta"
            onClick={() => {
              setDismissed(true);
              onResume(data.last_page);
            }}
          >
            이어 읽기 ({data.last_page}쪽으로)
          </button>
          <button
            type="button"
            className="cta-secondary"
            onClick={() => {
              setDismissed(true);
              onStartOver();
            }}
          >
            처음부터
          </button>
        </div>
      </div>
    </>
  );
}
