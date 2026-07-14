import { useEffect, useMemo, useState } from "react";
import { authHeaders, type AuthUser } from "../lib/auth";
import { authQuery, progressHeaders } from "../lib/client-id";

interface DayPoint {
  date: string;
  seconds: number;
}

interface MonthPoint {
  month: string;
  seconds: number;
}

interface TopBook {
  book_id: string;
  title: string;
  author: string;
  seconds: number;
  page_count: number;
  last_page: number;
}

interface StatsData {
  books: number;
  total_seconds: number;
  week_seconds: number;
  today_seconds: number;
  streak_days: number;
  daily: DayPoint[];
  heatmap: DayPoint[];
  monthly: MonthPoint[];
  top_books: TopBook[];
  best_day: { date: string; seconds: number } | null;
  avg_session_seconds: number;
  session_count: number;
  prev_week_seconds: number;
}

interface Props {
  user: AuthUser | null;
  open: boolean;
  onClose: () => void;
  onSelectBook?: (bookId: string) => void;
}

function fmtMin(seconds: number): string {
  if (!seconds) return "0분";
  const m = Math.round(seconds / 60);
  if (m < 60) return `${m}분`;
  const h = Math.floor(m / 60);
  const rem = m % 60;
  return rem ? `${h}시간 ${rem}분` : `${h}시간`;
}

function fmtHours(seconds: number): string {
  if (!seconds) return "0";
  const h = seconds / 3600;
  if (h < 1) return `${Math.round(seconds / 60)}분`;
  return `${h.toFixed(1)}h`;
}

function fmtShortDate(iso: string): string {
  // "2026-05-14" → "5월 14일"
  const m = iso.match(/^(\d{4})-(\d{2})-(\d{2})$/);
  if (!m) return iso;
  return `${parseInt(m[2], 10)}월 ${parseInt(m[3], 10)}일`;
}

function fmtMonth(iso: string): string {
  // "2026-05" → "26년 5월"
  const m = iso.match(/^(\d{4})-(\d{2})$/);
  if (!m) return iso;
  return `${m[1].slice(2)}년 ${parseInt(m[2], 10)}월`;
}

function daysBack(n: number): string[] {
  const out: string[] = [];
  const now = new Date();
  for (let i = n - 1; i >= 0; i--) {
    const d = new Date(now.getTime() - i * 86400_000);
    const iso = d.toISOString().slice(0, 10);
    out.push(iso);
  }
  return out;
}

type Range = "30d" | "12w" | "6mo";

export function StatsDashboard({ user, open, onClose, onSelectBook }: Props) {
  const [stats, setStats] = useState<StatsData | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [range, setRange] = useState<Range>("30d");

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    fetch("/me/stats", {
      headers: { ...progressHeaders(), ...authHeaders(user) },
    })
      .then((r) => (r.ok ? r.json() : Promise.reject(`HTTP ${r.status}`)))
      .then((d: StatsData) => {
        if (cancelled) return;
        setStats(d);
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
  }, [open, user]);

  useEffect(() => {
    if (!open) return;
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  // Zero-fill daily data over 30 days.
  const daily30 = useMemo(() => {
    if (!stats) return [] as DayPoint[];
    const map = new Map(stats.daily.map((d) => [d.date, d.seconds]));
    return daysBack(30).map((iso) => ({
      date: iso,
      seconds: map.get(iso) ?? 0,
    }));
  }, [stats]);

  // Group daily into 12 weeks (Mon-start) for the weekly view.
  const weekly12 = useMemo(() => {
    if (!stats) return [] as { week_start: string; seconds: number }[];
    const buckets = new Map<string, number>();
    // Walk last ~84 days; bucket by Monday of that ISO week.
    for (const d of daysBack(84)) {
      const date = new Date(d + "T00:00:00");
      const day = date.getDay(); // 0=Sun
      const mondayOffset = day === 0 ? -6 : 1 - day;
      const monday = new Date(date.getTime() + mondayOffset * 86400_000);
      const mIso = monday.toISOString().slice(0, 10);
      const sec =
        stats.daily.find((x) => x.date === d)?.seconds ??
        stats.heatmap.find((x) => x.date === d)?.seconds ??
        0;
      buckets.set(mIso, (buckets.get(mIso) ?? 0) + sec);
    }
    return Array.from(buckets.entries())
      .sort(([a], [b]) => (a < b ? -1 : 1))
      .slice(-12)
      .map(([week_start, seconds]) => ({ week_start, seconds }));
  }, [stats]);

  // Heatmap grid (13 weeks × 7 days). Each cell = a day's seconds; bucketed
  // into 5 intensity levels relative to the user's best day.
  const heatmap = useMemo(() => {
    if (!stats) return { cells: [] as { date: string; seconds: number; level: number }[], maxDay: 0 };
    const map = new Map(stats.heatmap.map((d) => [d.date, d.seconds]));
    const days = daysBack(91);
    const maxDay = Math.max(60, ...stats.heatmap.map((d) => d.seconds));
    const cells = days.map((iso) => {
      const s = map.get(iso) ?? 0;
      let level = 0;
      if (s > 0) {
        const ratio = s / maxDay;
        if (ratio >= 0.75) level = 4;
        else if (ratio >= 0.5) level = 3;
        else if (ratio >= 0.25) level = 2;
        else level = 1;
      }
      return { date: iso, seconds: s, level };
    });
    return { cells, maxDay };
  }, [stats]);

  const weekDeltaPct = useMemo(() => {
    if (!stats) return null;
    if (!stats.prev_week_seconds) return stats.week_seconds > 0 ? 100 : 0;
    return Math.round(
      ((stats.week_seconds - stats.prev_week_seconds) / stats.prev_week_seconds) *
        100
    );
  }, [stats]);

  if (!open) return null;

  const currentSeries =
    range === "30d"
      ? daily30.map((d) => ({ label: d.date, seconds: d.seconds }))
      : range === "12w"
      ? weekly12.map((w) => ({ label: w.week_start, seconds: w.seconds }))
      : (stats?.monthly ?? []).map((m) => ({ label: m.month, seconds: m.seconds }));

  const maxBar = Math.max(60, ...currentSeries.map((p) => p.seconds));

  return (
    <>
      <div className="stats-backdrop" onClick={onClose} aria-hidden="true" />
      <div className="stats-modal" role="dialog" aria-label="읽기 통계">
        <header className="stats-head">
          <h2>읽기 통계</h2>
          <button className="stats-close" onClick={onClose} aria-label="닫기" type="button">
            ×
          </button>
        </header>

        <div className="stats-body">
          {loading && (
            <div className="stats-empty">
              <div className="spinner" />
              <span>통계 불러오는 중…</span>
            </div>
          )}

          {!loading && error && (
            <div className="stats-empty">
              <span className="error">통계를 불러오지 못했어요: {error}</span>
            </div>
          )}

          {!loading && stats && (
            <>
              {/* Hero cards */}
              <div className="stats-hero">
                <HeroCard
                  label="오늘"
                  value={fmtMin(stats.today_seconds)}
                  accent
                />
                <HeroCard
                  label="이번 주"
                  value={fmtMin(stats.week_seconds)}
                  hint={
                    weekDeltaPct == null
                      ? undefined
                      : weekDeltaPct === 0
                      ? "지난주와 같음"
                      : weekDeltaPct > 0
                      ? `지난주 대비 +${weekDeltaPct}%`
                      : `지난주 대비 ${weekDeltaPct}%`
                  }
                  hintTone={
                    weekDeltaPct == null
                      ? undefined
                      : weekDeltaPct > 0
                      ? "good"
                      : weekDeltaPct < 0
                      ? "bad"
                      : undefined
                  }
                />
                <HeroCard label="누적" value={fmtMin(stats.total_seconds)} />
                <HeroCard label="읽은 책" value={`${stats.books}권`} />
                <HeroCard label="연속" value={`🔥 ${stats.streak_days}일`} />
              </div>

              {/* Heatmap */}
              <section className="stats-section">
                <header className="stats-section-head">
                  <h3>최근 90일 활동</h3>
                  <div className="stats-section-hint">
                    한 칸 = 하루 · 진할수록 오래 읽음
                  </div>
                </header>
                <div className="stats-heatmap">
                  <div className="stats-heatmap-grid">
                    {(() => {
                      const cells = heatmap.cells;
                      const rows: typeof cells[] = [[], [], [], [], [], [], []];
                      cells.forEach((c, i) => {
                        rows[i % 7].push(c);
                      });
                      return rows.map((row, ri) => (
                        <div className="stats-heatmap-row" key={ri}>
                          {row.map((c) => (
                            <span
                              key={c.date}
                              className={`stats-heatmap-cell stats-hm-l${c.level}`}
                              title={`${fmtShortDate(c.date)} · ${fmtMin(c.seconds)}`}
                            />
                          ))}
                        </div>
                      ));
                    })()}
                  </div>
                  <div className="stats-heatmap-scale">
                    <span>적게</span>
                    <span className="stats-heatmap-cell stats-hm-l0" />
                    <span className="stats-heatmap-cell stats-hm-l1" />
                    <span className="stats-heatmap-cell stats-hm-l2" />
                    <span className="stats-heatmap-cell stats-hm-l3" />
                    <span className="stats-heatmap-cell stats-hm-l4" />
                    <span>많이</span>
                  </div>
                </div>
              </section>

              {/* Bar chart with range toggle */}
              <section className="stats-section">
                <header className="stats-section-head">
                  <h3>독서 시간 추이</h3>
                  <div className="stats-tabs">
                    {(["30d", "12w", "6mo"] as Range[]).map((r) => (
                      <button
                        key={r}
                        type="button"
                        className={`stats-tab${range === r ? " stats-tab-on" : ""}`}
                        onClick={() => setRange(r)}
                      >
                        {r === "30d" ? "30일" : r === "12w" ? "12주" : "6개월"}
                      </button>
                    ))}
                  </div>
                </header>
                {currentSeries.length === 0 ? (
                  <div className="stats-empty-mini">데이터가 아직 없어요</div>
                ) : (
                  <div className="stats-bars">
                    {currentSeries.map((p, i) => {
                      const pct = maxBar ? (p.seconds / maxBar) * 100 : 0;
                      const label =
                        range === "6mo"
                          ? fmtMonth(p.label)
                          : range === "12w"
                          ? fmtShortDate(p.label)
                          : fmtShortDate(p.label);
                      return (
                        <div
                          key={`${p.label}-${i}`}
                          className={`stats-bar-col${p.seconds > 0 ? " is-on" : ""}`}
                          title={`${label} · ${fmtMin(p.seconds)}`}
                        >
                          <div className="stats-bar-track">
                            <div
                              className="stats-bar-fill"
                              style={{
                                height: `${Math.max(pct, p.seconds ? 3 : 0)}%`,
                                animationDelay: `${i * 0.012}s`,
                              }}
                            />
                          </div>
                        </div>
                      );
                    })}
                  </div>
                )}
                <div className="stats-bars-axis">
                  {currentSeries.length > 0 && (
                    <>
                      <span>{range === "6mo" ? fmtMonth(currentSeries[0].label) : fmtShortDate(currentSeries[0].label)}</span>
                      <span>{range === "6mo" ? fmtMonth(currentSeries[currentSeries.length - 1].label) : fmtShortDate(currentSeries[currentSeries.length - 1].label)}</span>
                    </>
                  )}
                </div>
              </section>

              {/* Top books */}
              <section className="stats-section">
                <header className="stats-section-head">
                  <h3>많이 읽은 책</h3>
                </header>
                {stats.top_books.length === 0 ? (
                  <div className="stats-empty-mini">아직 읽은 책이 없어요</div>
                ) : (
                  <ol className="stats-top">
                    {stats.top_books.map((b, i) => {
                      const pct = b.page_count
                        ? Math.min(100, Math.round((b.last_page / b.page_count) * 100))
                        : 0;
                      return (
                        <li
                          key={b.book_id}
                          className="stats-top-row"
                          onClick={() => onSelectBook?.(b.book_id)}
                        >
                          <span className="stats-top-rank">{i + 1}</span>
                          <div className="stats-top-cover">
                            <img
                              src={`/books/${b.book_id}/thumb${authQuery(user?.idToken)}`}
                              alt=""
                              loading="lazy"
                              onError={(e) => {
                                e.currentTarget.style.display = "none";
                              }}
                            />
                          </div>
                          <div className="stats-top-meta">
                            <div className="stats-top-title">{b.title}</div>
                            {b.author && <div className="stats-top-author">{b.author}</div>}
                            {b.page_count > 0 && (
                              <div className="stats-top-progress">
                                <div className="stats-top-progress-track">
                                  <div
                                    className="stats-top-progress-fill"
                                    style={{ width: `${pct}%` }}
                                  />
                                </div>
                                <span className="stats-top-progress-num">
                                  {b.last_page}/{b.page_count}쪽 · {pct}%
                                </span>
                              </div>
                            )}
                          </div>
                          <div className="stats-top-time">{fmtMin(b.seconds)}</div>
                        </li>
                      );
                    })}
                  </ol>
                )}
              </section>

              {/* Footer stats */}
              <section className="stats-footer-grid">
                <FooterStat
                  label="최고 기록"
                  value={
                    stats.best_day
                      ? `${fmtShortDate(stats.best_day.date)} · ${fmtMin(stats.best_day.seconds)}`
                      : "—"
                  }
                />
                <FooterStat
                  label="평균 세션"
                  value={
                    stats.session_count
                      ? `${fmtMin(stats.avg_session_seconds)} (${stats.session_count}회)`
                      : "—"
                  }
                />
                <FooterStat label="누적" value={fmtHours(stats.total_seconds)} />
              </section>
            </>
          )}
        </div>
      </div>
    </>
  );
}

interface HeroCardProps {
  label: string;
  value: string;
  hint?: string;
  hintTone?: "good" | "bad";
  accent?: boolean;
}

function HeroCard({ label, value, hint, hintTone, accent }: HeroCardProps) {
  return (
    <div className={`stats-hero-card${accent ? " stats-hero-accent" : ""}`}>
      <div className="stats-hero-label">{label}</div>
      <div className="stats-hero-value">{value}</div>
      {hint && (
        <div className={`stats-hero-hint${hintTone ? ` stats-hero-hint-${hintTone}` : ""}`}>
          {hint}
        </div>
      )}
    </div>
  );
}

function FooterStat({ label, value }: { label: string; value: string }) {
  return (
    <div className="stats-footer-stat">
      <div className="stats-footer-label">{label}</div>
      <div className="stats-footer-value">{value}</div>
    </div>
  );
}
