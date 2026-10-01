import { useEffect, useRef, useState } from "react";
import { getGeo } from "../lib/geo";

interface UserClaims {
  email?: string | null;
  name?: string | null;
}

interface Props {
  user: UserClaims | null;
  onUploaded: (bookId: string) => void;
  onShowLegal?: () => void;
}

const CATEGORIES = [
  "소설",
  "시·에세이",
  "인문",
  "자기계발",
  "비즈니스",
  "과학·기술",
  "역사",
  "예술",
  "어린이",
  "기타",
];

const ACCEPT = ".pdf,.epub,application/pdf,application/epub+zip";

function cleanFilenameToTitle(name: string): string {
  let base = name.replace(/\.(pdf|epub)$/i, "");
  base = base.replace(/[_-]+/g, " ");
  base = base.replace(/\s+/g, " ").trim();
  // Strip common noise: trailing "_v2", "(1)", "[final]" etc.
  base = base.replace(/[(\[][^)\]]*[)\]]/g, "").trim();
  return base.charAt(0).toUpperCase() + base.slice(1);
}

function humanSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function authHeaders(user: UserClaims | null): Record<string, string> {
  const token = typeof window !== "undefined" ? localStorage.getItem("g_id_token") : null;
  if (user && token) return { Authorization: `Bearer ${token}` };
  return {};
}

export function UploadWizard({ user, onUploaded, onShowLegal }: Props) {
  const [stage, setStage] = useState<"drop" | "detail" | "uploading">("drop");
  const [file, setFile] = useState<File | null>(null);
  const [dragging, setDragging] = useState(false);

  const [title, setTitle] = useState("");
  const [author, setAuthor] = useState("");
  const [category, setCategory] = useState<string | null>(null);
  const [description, setDescription] = useState("");

  // Detailed metadata (collapsed by default)
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [subtitle, setSubtitle] = useState("");
  const [translator, setTranslator] = useState("");
  const [publisher, setPublisher] = useState("");
  const [publishedYear, setPublishedYear] = useState("");
  const [language, setLanguage] = useState<string>("");
  const [isbn, setIsbn] = useState("");
  const [seriesName, setSeriesName] = useState("");
  const [seriesIndex, setSeriesIndex] = useState("");
  const [tagInput, setTagInput] = useState("");
  const [tags, setTags] = useState<string[]>([]);

  const [coverFile, setCoverFile] = useState<File | null>(null);
  const [coverUrl, setCoverUrl] = useState<string | null>(null);
  const [coverGenerating, setCoverGenerating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [uploadStep, setUploadStep] = useState(0);
  // Copyright warranty checkbox — required before /upload accepts the file.
  // Uploaded timestamp is recorded server-side as evidence under Korean
  // copyright act §102 (OSP safe harbor) and §103 (notice-and-takedown).
  const [consent, setConsent] = useState(false);

  const dropRef = useRef<HTMLLabelElement>(null);

  useEffect(() => {
    if (!coverFile) {
      setCoverUrl(null);
      return;
    }
    const url = URL.createObjectURL(coverFile);
    setCoverUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [coverFile]);

  function acceptFile(f: File) {
    const lower = f.name.toLowerCase();
    if (
      !lower.endsWith(".pdf") &&
      !lower.endsWith(".epub") &&
      !f.type.includes("pdf") &&
      !f.type.includes("epub")
    ) {
      setError("PDF 또는 EPUB 파일만 올릴 수 있어요.");
      return;
    }
    setError(null);
    setFile(f);
    setTitle((t) => t || cleanFilenameToTitle(f.name));
    setStage("detail");
  }

  function reset() {
    setFile(null);
    setStage("drop");
    setCoverFile(null);
    setCoverUrl(null);
    setTitle("");
    setAuthor("");
    setCategory(null);
    setDescription("");
    setError(null);
    setUploadStep(0);
    setAdvancedOpen(false);
    setSubtitle("");
    setTranslator("");
    setPublisher("");
    setPublishedYear("");
    setLanguage("");
    setIsbn("");
    setSeriesName("");
    setSeriesIndex("");
    setTagInput("");
    setTags([]);
  }

  function commitTagInput() {
    const fresh = tagInput
      .split(",")
      .map((t) => t.trim())
      .filter(Boolean);
    if (!fresh.length) return;
    const lower = new Set(tags.map((t) => t.toLowerCase()));
    const merged = [...tags];
    for (const t of fresh) {
      if (t.length <= 40 && !lower.has(t.toLowerCase())) {
        merged.push(t);
        lower.add(t.toLowerCase());
        if (merged.length >= 20) break;
      }
    }
    setTags(merged);
    setTagInput("");
  }

  async function generateAICover() {
    if (!title.trim()) {
      setError("표지를 만들려면 책 제목이 필요해요.");
      return;
    }
    setError(null);
    setCoverGenerating(true);
    try {
      const r = await fetch("/covers/generate", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          title: title.trim(),
          description: description.trim(),
          category: category ?? "",
        }),
      });
      if (!r.ok) {
        const t = await r.text();
        throw new Error(t || `HTTP ${r.status}`);
      }
      const blob = await r.blob();
      const f = new File([blob], `cover_${Date.now()}.png`, { type: "image/png" });
      setCoverFile(f);
    } catch (e) {
      setError(`AI 표지 생성 실패: ${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setCoverGenerating(false);
    }
  }

  async function submit() {
    if (!file) return;
    if (!consent) {
      setError("업로드하려면 본인이 권리를 가진 책임에 동의해야 합니다.");
      return;
    }
    setError(null);
    setStage("uploading");
    setUploadStep(0);

    // Optimistic step progression so the user sees movement during the long
    // upload + Gemini analysis on the backend.
    const stepTimer = window.setInterval(() => {
      setUploadStep((s) => Math.min(3, s + 1));
    }, 1800);

    try {
      const fd = new FormData();
      fd.append("file", file);
      const t = title.trim();
      if (t) fd.append("title_override", t);
      const a = author.trim();
      if (a) fd.append("author", a);
      if (category) fd.append("category", category);
      const d = description.trim();
      if (d) fd.append("description", d);
      if (coverFile) fd.append("cover", coverFile);
      // Detailed metadata
      const merged = tagInput.trim()
        ? Array.from(new Set([...tags, ...tagInput.split(",").map((x) => x.trim()).filter(Boolean)]))
        : tags;
      const extras: Record<string, string> = {
        subtitle: subtitle.trim(),
        translator: translator.trim(),
        publisher: publisher.trim(),
        published_year: publishedYear.trim(),
        language: language.trim(),
        isbn: isbn.trim(),
        series_name: seriesName.trim(),
        series_index: seriesIndex.trim(),
      };
      for (const [k, v] of Object.entries(extras)) {
        if (v) fd.append(k, v);
      }
      if (merged.length) fd.append("tags", merged.join(","));
      fd.append("consent_accepted", "true");

      // Best-effort location → ~10% music tint (place/weather/season). Skipped
      // silently if the user denies permission.
      const geo = await getGeo();
      if (geo) {
        fd.append("lat", String(geo.lat));
        fd.append("lon", String(geo.lon));
      }

      const r = await fetch("/upload", {
        method: "POST",
        body: fd,
        headers: { ...authHeaders(user) },
      });
      if (!r.ok) throw new Error(`업로드 실패 (${r.status})`);
      const data = await r.json();
      window.clearInterval(stepTimer);
      setUploadStep(4);
      // Brief celebratory pause so the "완료!" state can be seen.
      window.setTimeout(() => onUploaded(data.book_id), 700);
    } catch (e) {
      window.clearInterval(stepTimer);
      setError(e instanceof Error ? e.message : String(e));
      setStage("detail");
    }
  }

  if (stage === "drop") {
    return (
      <div className="uw">
        <label
          ref={dropRef}
          className={`uw-drop${dragging ? " uw-drop-active" : ""}`}
          onDragOver={(e) => {
            e.preventDefault();
            setDragging(true);
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={(e) => {
            e.preventDefault();
            setDragging(false);
            const f = e.dataTransfer.files?.[0];
            if (f) acceptFile(f);
          }}
        >
          <div className="uw-drop-icon">
            <svg width="44" height="44" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
              <path d="M4 19V5a2 2 0 0 1 2-2h8l6 6v10a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2Z" />
              <path d="M14 3v6h6" />
              <path d="M12 18v-6" />
              <path d="m9 15 3-3 3 3" />
            </svg>
          </div>
          <div className="uw-drop-title">파일을 끌어다 놓으세요</div>
          <div className="uw-drop-sub">또는 클릭해서 선택 — PDF · EPUB</div>
          <div className="uw-drop-cta">파일 선택</div>
          <input
            type="file"
            accept={ACCEPT}
            onChange={(e) => {
              const f = e.target.files?.[0];
              if (f) acceptFile(f);
            }}
          />
        </label>
        {error && <div className="uw-error">{error}</div>}
        <div className="uw-features">
          <div className="uw-feature">
            <span className="uw-feature-icon">📖</span>
            <span>5가지 읽기 모드</span>
          </div>
          <div className="uw-feature">
            <span className="uw-feature-icon">🎵</span>
            <span>분위기에 맞는 음악</span>
          </div>
          <div className="uw-feature">
            <span className="uw-feature-icon">🎨</span>
            <span>AI 표지 · 장 그림 생성</span>
          </div>
        </div>
      </div>
    );
  }

  if (stage === "uploading") {
    const STEPS = [
      "파일 업로드 중",
      "페이지 텍스트 추출",
      "AI가 분위기 분석 중",
      "목차·요약 생성 중",
      "완료!",
    ];
    return (
      <div className="uw">
        <div className="uw-uploading">
          <div className="uw-uploading-pulse" />
          <h3>책을 준비하고 있어요</h3>
          <ul className="uw-steps">
            {STEPS.map((label, i) => {
              const state =
                i < uploadStep ? "done" : i === uploadStep ? "active" : "pending";
              return (
                <li key={label} className={`uw-step uw-step-${state}`}>
                  <span className="uw-step-dot">
                    {state === "done" ? "✓" : state === "active" ? <span className="uw-step-spin" /> : i + 1}
                  </span>
                  <span className="uw-step-label">{label}</span>
                </li>
              );
            })}
          </ul>
        </div>
      </div>
    );
  }

  // stage === "detail"
  return (
    <div className="uw">
      <div className="uw-detail">
        <div className="uw-detail-left">
          <div className="uw-cover">
            {coverUrl ? (
              <>
                <img src={coverUrl} alt="표지 미리보기" />
                {coverGenerating && <div className="uw-cover-overlay"><div className="spinner" /></div>}
              </>
            ) : coverGenerating ? (
              <div className="uw-cover-empty">
                <div className="spinner" />
                <span>AI가 표지를 그리고 있어요…</span>
              </div>
            ) : (
              <div className="uw-cover-empty">
                <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round">
                  <rect x="3" y="3" width="18" height="18" rx="2" />
                  <circle cx="9" cy="9" r="2" />
                  <path d="m21 15-5-5L5 21" />
                </svg>
                <span>표지를 추가하세요</span>
              </div>
            )}
          </div>

          <div className="uw-cover-actions">
            <label className="uw-btn-ghost">
              <input
                type="file"
                accept="image/png,image/jpeg,image/webp,image/gif"
                onChange={(e) => {
                  const f = e.target.files?.[0];
                  if (f) setCoverFile(f);
                }}
              />
              직접 올리기
            </label>
            <button
              type="button"
              className="uw-btn-magic"
              disabled={coverGenerating || !title.trim()}
              onClick={generateAICover}
              title={!title.trim() ? "표지를 만들려면 책 제목을 먼저 입력해주세요" : "Nano Banana로 표지 생성"}
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                <path d="m12 3 1.9 5.8L20 10l-5 4.3L16.3 21 12 17.8 7.7 21 9 14.3 4 10l6.1-1.2Z" />
              </svg>
              {coverGenerating ? "그리는 중…" : "AI로 만들기"}
            </button>
          </div>
        </div>

        <div className="uw-detail-right">
          <div className="uw-file-chip">
            <span className="uw-file-icon">
              {file?.name.toLowerCase().endsWith(".epub") ? "📗" : "📕"}
            </span>
            <div className="uw-file-meta">
              <div className="uw-file-name">{file?.name}</div>
              <div className="uw-file-size">{file ? humanSize(file.size) : ""}</div>
            </div>
            <button
              type="button"
              className="uw-file-x"
              onClick={reset}
              aria-label="다른 파일 선택"
            >
              ×
            </button>
          </div>

          <label className="uw-field">
            <span className="uw-label">제목</span>
            <input
              className="uw-input uw-input-title"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="책 제목"
              maxLength={300}
            />
          </label>

          <label className="uw-field">
            <span className="uw-label">작가</span>
            <input
              className="uw-input"
              value={author}
              onChange={(e) => setAuthor(e.target.value)}
              placeholder="작가 이름 (선택)"
              maxLength={200}
            />
          </label>

          <div className="uw-field">
            <span className="uw-label">분류</span>
            <div className="uw-chips">
              {CATEGORIES.map((c) => (
                <button
                  type="button"
                  key={c}
                  className={`uw-chip${category === c ? " uw-chip-active" : ""}`}
                  onClick={() => setCategory(category === c ? null : c)}
                >
                  {c}
                </button>
              ))}
            </div>
          </div>

          <label className="uw-field">
            <span className="uw-label">소개</span>
            <textarea
              className="uw-input uw-textarea"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="작가·줄거리·추천 포인트… (선택)"
              rows={4}
              maxLength={4000}
            />
          </label>

          <button
            type="button"
            className={`uw-disclosure${advancedOpen ? " uw-disclosure-open" : ""}`}
            onClick={() => setAdvancedOpen((v) => !v)}
            aria-expanded={advancedOpen}
          >
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
              <polyline points="6 9 12 15 18 9" />
            </svg>
            자세한 정보 {advancedOpen ? "접기" : "추가"}
          </button>

          {advancedOpen && (
            <div className="uw-advanced">
              <label className="uw-field">
                <span className="uw-label">부제</span>
                <input
                  className="uw-input"
                  value={subtitle}
                  onChange={(e) => setSubtitle(e.target.value)}
                  placeholder="부제 (선택)"
                  maxLength={300}
                />
              </label>

              <div className="uw-grid-2">
                <label className="uw-field">
                  <span className="uw-label">시리즈</span>
                  <input
                    className="uw-input"
                    value={seriesName}
                    onChange={(e) => setSeriesName(e.target.value)}
                    placeholder="예: 해리포터"
                    maxLength={200}
                  />
                </label>
                <label className="uw-field">
                  <span className="uw-label">권 번호</span>
                  <input
                    className="uw-input"
                    value={seriesIndex}
                    onChange={(e) => setSeriesIndex(e.target.value)}
                    placeholder="예: 3"
                    maxLength={20}
                  />
                </label>
              </div>

              <label className="uw-field">
                <span className="uw-label">옮긴이</span>
                <input
                  className="uw-input"
                  value={translator}
                  onChange={(e) => setTranslator(e.target.value)}
                  placeholder="번역자 (선택)"
                  maxLength={200}
                />
              </label>

              <div className="uw-grid-2">
                <label className="uw-field">
                  <span className="uw-label">출판사</span>
                  <input
                    className="uw-input"
                    value={publisher}
                    onChange={(e) => setPublisher(e.target.value)}
                    placeholder="출판사"
                    maxLength={200}
                  />
                </label>
                <label className="uw-field">
                  <span className="uw-label">출판연도</span>
                  <input
                    className="uw-input"
                    value={publishedYear}
                    onChange={(e) => setPublishedYear(e.target.value.replace(/[^0-9]/g, "").slice(0, 4))}
                    placeholder="예: 2024"
                    inputMode="numeric"
                    maxLength={4}
                  />
                </label>
              </div>

              <div className="uw-grid-2">
                <label className="uw-field">
                  <span className="uw-label">언어</span>
                  <select
                    className="uw-input"
                    value={language}
                    onChange={(e) => setLanguage(e.target.value)}
                  >
                    <option value="">선택 안 함</option>
                    <option value="한국어">한국어</option>
                    <option value="English">English</option>
                    <option value="日本語">日本語</option>
                    <option value="中文">中文</option>
                    <option value="Español">Español</option>
                    <option value="Français">Français</option>
                    <option value="Deutsch">Deutsch</option>
                    <option value="기타">기타</option>
                  </select>
                </label>
                <label className="uw-field">
                  <span className="uw-label">ISBN</span>
                  <input
                    className="uw-input"
                    value={isbn}
                    onChange={(e) => setIsbn(e.target.value)}
                    placeholder="예: 978-89-..."
                    maxLength={40}
                  />
                </label>
              </div>

              <div className="uw-field">
                <span className="uw-label">태그</span>
                <div className="uw-tags">
                  {tags.map((t) => (
                    <span key={t} className="uw-tag">
                      {t}
                      <button
                        type="button"
                        className="uw-tag-x"
                        onClick={() => setTags(tags.filter((x) => x !== t))}
                        aria-label={`태그 ${t} 제거`}
                      >
                        ×
                      </button>
                    </span>
                  ))}
                  <input
                    className="uw-tag-input"
                    value={tagInput}
                    onChange={(e) => setTagInput(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter" || e.key === ",") {
                        e.preventDefault();
                        commitTagInput();
                      } else if (e.key === "Backspace" && !tagInput && tags.length) {
                        setTags(tags.slice(0, -1));
                      }
                    }}
                    onBlur={commitTagInput}
                    placeholder={tags.length ? "" : "철학, 윤리, 현대… (Enter로 추가)"}
                    maxLength={40}
                  />
                </div>
              </div>
            </div>
          )}

          {error && <div className="uw-error">{error}</div>}

          <label className="uw-consent">
            <input
              type="checkbox"
              checked={consent}
              onChange={(e) => setConsent(e.target.checked)}
            />
            <span className="uw-consent-text">
              본인은 이 책의 저작권을 보유하거나 합법적인 권원 (저작권자
              허락 / 보호기간 만료 / 사적이용 등)에 따라 업로드한다는 사실을
              확인합니다.
              {onShowLegal && (
                <>
                  {" "}
                  <button
                    type="button"
                    className="uw-consent-link"
                    onClick={(e) => {
                      e.preventDefault();
                      onShowLegal();
                    }}
                  >
                    이용약관 자세히 보기
                  </button>
                </>
              )}
            </span>
          </label>

          <div className="uw-submit-row">
            <button type="button" className="uw-btn-ghost" onClick={reset}>
              다른 파일 선택
            </button>
            <button
              type="button"
              className="uw-btn-primary"
              disabled={!file || !title.trim() || !consent}
              onClick={submit}
              title={!consent ? "약관 동의 후 업로드할 수 있어요" : ""}
            >
              라이브러리에 추가
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
