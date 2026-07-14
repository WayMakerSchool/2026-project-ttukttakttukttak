import { useEffect, useMemo, useState } from "react";

interface Character {
  name: string;
  description: string;
  first_page: number;
  importance: number;
}

interface Relationship {
  from: string;
  to: string;
  label: string;
  kind: string;
}

interface Data {
  characters: Character[];
  relationships: Relationship[];
}

interface Props {
  bookId: string;
  open: boolean;
  onClose: () => void;
  /** Omit for sources without page numbers (camera reader) — hides jumps. */
  onJump?: (page: number) => void;
  /** Override the fetch URL (default: /books/{bookId}/characters). */
  endpoint?: string;
}

const CANVAS = 420;

const KIND_COLORS: Record<string, string> = {
  friend: "hsl(150 55% 60%)",
  enemy: "hsl(0 65% 60%)",
  romance: "hsl(335 75% 68%)",
  family: "hsl(40 75% 62%)",
  colleague: "hsl(210 60% 64%)",
  mentor: "hsl(280 55% 65%)",
  rival: "hsl(20 75% 62%)",
  other: "hsl(0 0% 65%)",
};

const KIND_LABELS_KO: Record<string, string> = {
  friend: "친구",
  enemy: "적",
  romance: "연인",
  family: "가족",
  colleague: "동료",
  mentor: "스승·제자",
  rival: "라이벌",
  other: "관계",
};

function firstGrapheme(name: string): string {
  return Array.from(name.trim())[0] ?? "?";
}

export function CharactersPanel({ bookId, open, onClose, onJump, endpoint }: Props) {
  const [data, setData] = useState<Data | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [hovered, setHovered] = useState<string | null>(null);
  // Drives the "spread out from center" entrance animation. Flips to true
  // one frame after data lands so CSS transitions on `<g transform>` fire.
  const [spread, setSpread] = useState(false);
  // Replay counter — bumping it re-triggers the spread animation.
  const [replay, setReplay] = useState(0);

  useEffect(() => {
    if (!open || !bookId) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    setSelected(null);
    setHovered(null);
    setSpread(false);
    fetch(endpoint ?? `/books/${bookId}/characters`)
      .then((r) => (r.ok ? r.json() : Promise.reject(`status ${r.status}`)))
      .then((d) => {
        if (cancelled) return;
        setData(d as Data);
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
  }, [bookId, open, endpoint]);

  // Kick off the spread animation as soon as data is ready (or replayed).
  useEffect(() => {
    if (!data || !data.characters.length) return;
    setSpread(false);
    const t = window.setTimeout(() => setSpread(true), 40);
    return () => window.clearTimeout(t);
  }, [data, replay]);

  useEffect(() => {
    if (!open) return;
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  // Layout: importance-weighted circular layout — heaviest characters get
  // larger radii and slightly inward placement; supporting characters live
  // on the outer ring.
  const layout = useMemo(() => {
    const m = new Map<string, { x: number; y: number; r: number; angle: number }>();
    if (!data?.characters?.length) return m;
    const n = data.characters.length;
    const cx = CANVAS / 2;
    const cy = CANVAS / 2;
    // Sort by importance desc so the leading character starts at the top
    // and the eye is drawn there first.
    const sorted = [...data.characters].sort((a, b) => b.importance - a.importance);
    sorted.forEach((c, i) => {
      const angle = (i / n) * 2 * Math.PI - Math.PI / 2;
      // Main characters (importance>=4) sit slightly inside, supporting
      // characters sit on the outer ring.
      const isMain = c.importance >= 4;
      const radius = CANVAS * (isMain ? 0.32 : 0.42);
      m.set(c.name, {
        x: cx + radius * Math.cos(angle),
        y: cy + radius * Math.sin(angle),
        r: 14 + c.importance * 4,
        angle,
      });
    });
    return m;
  }, [data]);

  const focusName = selected ?? hovered;

  // Edges touching the focused character, used for highlight emphasis.
  const focusEdges = useMemo(() => {
    if (!focusName || !data) return new Set<number>();
    return new Set(
      data.relationships
        .map((r, i) => ({ r, i }))
        .filter(({ r }) => r.from === focusName || r.to === focusName)
        .map(({ i }) => i)
    );
  }, [focusName, data]);

  // Connected characters of the SELECTED node, used in the side detail card.
  const connections = useMemo(() => {
    if (!selected || !data) return [] as { rel: Relationship; other: string }[];
    return data.relationships
      .filter((r) => r.from === selected || r.to === selected)
      .map((rel) => ({
        rel,
        other: rel.from === selected ? rel.to : rel.from,
      }));
  }, [selected, data]);

  if (!open) return null;

  const selectedChar = data?.characters.find((c) => c.name === selected) ?? null;

  const cx = CANVAS / 2;
  const cy = CANVAS / 2;

  return (
    <>
      <div className="toc-backdrop" onClick={onClose} aria-hidden="true" />
      <aside className="toc-drawer chars-drawer" role="dialog" aria-label="인물 관계도">
        <header className="toc-header">
          <h2>인물 관계도</h2>
          <div className="chars-head-actions">
            {data && data.characters.length > 0 && (
              <button
                type="button"
                className="chars-replay"
                onClick={() => setReplay((r) => r + 1)}
                title="펼치기 다시 보기"
                aria-label="펼치기 다시 보기"
              >
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <polyline points="23 4 23 10 17 10" />
                  <path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10" />
                </svg>
              </button>
            )}
            <button className="toc-close" onClick={onClose} aria-label="닫기" type="button">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <line x1="18" y1="6" x2="6" y2="18" />
                <line x1="6" y1="6" x2="18" y2="18" />
              </svg>
            </button>
          </div>
        </header>

        <div className="toc-body chars-body">
          {loading && (
            <div className="toc-state">
              <div className="spinner" />
              <span>인물 분석 중… (책 길이에 따라 10~30초)</span>
            </div>
          )}
          {!loading && error && (
            <div className="toc-state">
              <span className="error">{error}</span>
            </div>
          )}
          {!loading && data && data.characters.length === 0 && (
            <div className="toc-state">
              이 책에서는 뚜렷한 등장인물을 찾지 못했어요.
            </div>
          )}
          {!loading && data && data.characters.length > 0 && (
            <>
              <div className="chars-stage">
                <svg
                  className={`chars-graph${spread ? " is-spread" : ""}`}
                  viewBox={`0 0 ${CANVAS} ${CANVAS}`}
                  width="100%"
                  height={CANVAS}
                >
                  <defs>
                    <radialGradient id="char-node-fill" cx="35%" cy="30%">
                      <stop offset="0%" stopColor="color-mix(in srgb, var(--accent) 70%, #fff)" />
                      <stop offset="100%" stopColor="var(--accent)" />
                    </radialGradient>
                    <radialGradient id="char-node-fill-dim" cx="35%" cy="30%">
                      <stop offset="0%" stopColor="color-mix(in srgb, var(--accent) 30%, var(--bg-elev))" />
                      <stop offset="100%" stopColor="color-mix(in srgb, var(--accent) 18%, var(--bg-surface))" />
                    </radialGradient>
                    <filter id="char-node-glow" x="-50%" y="-50%" width="200%" height="200%">
                      <feGaussianBlur stdDeviation="3" result="b" />
                      <feMerge>
                        <feMergeNode in="b" />
                        <feMergeNode in="SourceGraphic" />
                      </feMerge>
                    </filter>
                  </defs>

                  {/* Background concentric rings — subtle depth cue */}
                  <circle cx={cx} cy={cy} r={CANVAS * 0.32} fill="none" stroke="var(--border)" strokeDasharray="2 4" opacity={0.35} />
                  <circle cx={cx} cy={cy} r={CANVAS * 0.42} fill="none" stroke="var(--border)" strokeDasharray="2 4" opacity={0.2} />

                  {/* Edges */}
                  {data.relationships.map((r, i) => {
                    const a = layout.get(r.from);
                    const b = layout.get(r.to);
                    if (!a || !b) return null;
                    const isFocus = focusEdges.has(i);
                    const dim = focusName && !isFocus ? 0.08 : isFocus ? 1 : 0.5;
                    const color = KIND_COLORS[r.kind] ?? KIND_COLORS.other;
                    const showLabel = !focusName || isFocus;
                    // Delay scales with character count so edges paint AFTER
                    // nodes land in place.
                    const delay = 0.55 + Math.min(0.9, data.characters.length * 0.06);
                    return (
                      <g
                        key={`${r.from}-${r.to}-${i}`}
                        className="chars-edge"
                        style={{
                          opacity: spread ? dim : 0,
                          transition: `opacity 0.5s ease ${delay}s`,
                        }}
                      >
                        <line
                          x1={spread ? a.x : cx}
                          y1={spread ? a.y : cy}
                          x2={spread ? b.x : cx}
                          y2={spread ? b.y : cy}
                          stroke={color}
                          strokeWidth={isFocus ? 2.4 : 1.5}
                          strokeLinecap="round"
                          style={{
                            transition: `x1 0.9s cubic-bezier(0.34,1.56,0.64,1), y1 0.9s cubic-bezier(0.34,1.56,0.64,1), x2 0.9s cubic-bezier(0.34,1.56,0.64,1), y2 0.9s cubic-bezier(0.34,1.56,0.64,1), stroke-width 0.2s ease`,
                          }}
                        />
                        {showLabel && r.label && (
                          <g
                            transform={`translate(${(a.x + b.x) / 2}, ${(a.y + b.y) / 2})`}
                            style={{ pointerEvents: "none" }}
                          >
                            <rect
                              x={-r.label.length * 3.2 - 5}
                              y={-7}
                              width={r.label.length * 6.4 + 10}
                              height={14}
                              rx={7}
                              fill="var(--bg-surface)"
                              opacity={0.85}
                            />
                            <text
                              textAnchor="middle"
                              dy="3.5"
                              fontSize="9.5"
                              fill="var(--text-secondary)"
                              fontWeight={isFocus ? 600 : 400}
                            >
                              {r.label}
                            </text>
                          </g>
                        )}
                      </g>
                    );
                  })}

                  {/* Nodes */}
                  {data.characters.map((c, i) => {
                    const p = layout.get(c.name);
                    if (!p) return null;
                    const isSel = selected === c.name;
                    const isHov = hovered === c.name;
                    const isFocus = focusName === c.name;
                    const connected =
                      focusName &&
                      data.relationships.some(
                        (r) =>
                          (r.from === focusName && r.to === c.name) ||
                          (r.to === focusName && r.from === c.name)
                      );
                    const isFaded = !!focusName && !isFocus && !connected;
                    const stagger = i * 0.07;
                    const tx = spread ? p.x : cx;
                    const ty = spread ? p.y : cy;
                    const scale = isSel ? 1.18 : isHov ? 1.1 : 1;
                    return (
                      <g
                        key={c.name}
                        className="chars-node"
                        style={{
                          transform: `translate(${tx}px, ${ty}px)`,
                          transition: `transform 0.95s cubic-bezier(0.34,1.56,0.64,1) ${stagger}s, opacity 0.3s ease`,
                          opacity: spread ? (isFaded ? 0.22 : 1) : 0,
                          cursor: "pointer",
                          // Use SVG-friendly transform via CSS; works in modern browsers.
                          transformBox: "fill-box",
                          transformOrigin: "center",
                        }}
                        onClick={() => setSelected(isSel ? null : c.name)}
                        onMouseEnter={() => setHovered(c.name)}
                        onMouseLeave={() => setHovered((h) => (h === c.name ? null : h))}
                      >
                        {/* Idle bob applied to inner group so layout transform stays clean */}
                        <g
                          className="chars-node-bob"
                          style={{
                            animationDelay: `${(i * 0.13) % 2.4}s`,
                            transform: `scale(${scale})`,
                            transition: "transform 0.2s ease",
                            transformBox: "fill-box",
                            transformOrigin: "center",
                          }}
                        >
                          {/* Pulse ring when selected */}
                          {isSel && (
                            <circle
                              r={p.r + 6}
                              fill="none"
                              stroke="var(--accent)"
                              strokeWidth={2}
                              className="chars-pulse"
                            />
                          )}
                          {/* Glow halo on focus */}
                          {isFocus && (
                            <circle
                              r={p.r + 2}
                              fill="var(--accent)"
                              opacity={0.18}
                              filter="url(#char-node-glow)"
                            />
                          )}
                          <circle
                            r={p.r}
                            fill={isFaded ? "url(#char-node-fill-dim)" : "url(#char-node-fill)"}
                            stroke={isSel ? "var(--text-primary)" : "rgba(255,255,255,0.18)"}
                            strokeWidth={isSel ? 2 : 1}
                          />
                          {/* Initial letter */}
                          <text
                            textAnchor="middle"
                            dy={p.r * 0.18}
                            fontSize={Math.max(11, p.r * 0.8)}
                            fontWeight={700}
                            fill="#fff"
                            style={{ pointerEvents: "none" }}
                          >
                            {firstGrapheme(c.name)}
                          </text>
                          {/* Name below */}
                          <text
                            textAnchor="middle"
                            dy={p.r + 14}
                            fontSize="11.5"
                            fontWeight={c.importance >= 4 ? 700 : 500}
                            fill="var(--text-primary)"
                            style={{ pointerEvents: "none" }}
                          >
                            {c.name}
                          </text>
                          {/* Importance dots */}
                          <text
                            textAnchor="middle"
                            dy={p.r + 26}
                            fontSize="9"
                            fill="var(--accent)"
                            style={{ pointerEvents: "none", letterSpacing: "1px" }}
                          >
                            {"●".repeat(Math.min(5, c.importance))}
                          </text>
                        </g>
                      </g>
                    );
                  })}
                </svg>
              </div>

              {selectedChar && (
                <div className="chars-detail chars-detail-rich">
                  <div className="chars-detail-head">
                    <div className="chars-detail-title">
                      <div className="chars-detail-avatar">{firstGrapheme(selectedChar.name)}</div>
                      <div>
                        <strong>{selectedChar.name}</strong>
                        <div className="chars-detail-sub">
                          중요도 <span className="chars-importance-dots">{"●".repeat(Math.min(5, selectedChar.importance))}</span>
                          <span className="chars-detail-sep">·</span>
                          관계 {connections.length}개
                        </div>
                      </div>
                    </div>
                    {onJump && selectedChar.first_page > 0 && (
                      <button
                        type="button"
                        className="chars-jump"
                        onClick={() => {
                          onJump(selectedChar.first_page);
                          onClose();
                        }}
                      >
                        p.{selectedChar.first_page} 첫 등장 →
                      </button>
                    )}
                  </div>
                  {selectedChar.description && (
                    <p className="chars-detail-desc">{selectedChar.description}</p>
                  )}
                  {connections.length > 0 && (
                    <ul className="chars-connections">
                      {connections.map(({ rel, other }, idx) => {
                        const color = KIND_COLORS[rel.kind] ?? KIND_COLORS.other;
                        const kindLabel = KIND_LABELS_KO[rel.kind] ?? rel.kind;
                        return (
                          <li
                            key={`${rel.from}-${rel.to}-${idx}`}
                            className="chars-connection"
                            onClick={() => setSelected(other)}
                          >
                            <span
                              className="chars-connection-kind"
                              style={{ background: color }}
                            >
                              {kindLabel}
                            </span>
                            <span className="chars-connection-name">{other}</span>
                            {rel.label && (
                              <span className="chars-connection-label">{rel.label}</span>
                            )}
                          </li>
                        );
                      })}
                    </ul>
                  )}
                </div>
              )}

              <div className="chars-list">
                <div className="chars-list-label">등장인물 {data.characters.length}명</div>
                <ul>
                  {data.characters.map((c) => (
                    <li key={c.name}>
                      <button
                        type="button"
                        className={`chars-list-item${selected === c.name ? " is-selected" : ""}`}
                        onClick={() => setSelected(c.name === selected ? null : c.name)}
                        onMouseEnter={() => setHovered(c.name)}
                        onMouseLeave={() => setHovered((h) => (h === c.name ? null : h))}
                      >
                        <span className="chars-list-importance" data-level={c.importance}>
                          {"●".repeat(c.importance)}
                        </span>
                        <span className="chars-list-name">{c.name}</span>
                        {c.first_page > 0 && (
                          <span className="chars-list-page">p.{c.first_page}</span>
                        )}
                      </button>
                    </li>
                  ))}
                </ul>
              </div>
            </>
          )}
        </div>
      </aside>
    </>
  );
}
