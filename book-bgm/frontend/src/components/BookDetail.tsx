import { useEffect, useState } from "react";
import { authHeaders, type AuthUser } from "../lib/auth";
import { authQuery } from "../lib/client-id";

export interface BookDetailData {
  id: string;
  title: string;
  page_count: number;
  size_bytes: number;
  uploaded_at: string;
  audio_status?: "pending" | "generating" | "ready" | "failed";
  description?: string | null;
  uploader_email?: string | null;
  uploader_name?: string | null;
  format?: "pdf" | "epub";
}

export interface Review {
  id: number;
  author: string | null;
  rating: number;
  text: string;
  created_at: string;
}

interface Props {
  bookId: string;
  user: AuthUser | null;
  onBack: () => void;
  onRead: (book: BookDetailData) => void;
  onDeleted: () => void;
}

function formatDate(iso: string): string {
  try {
    return new Date(iso).toLocaleDateString("ko-KR", {
      year: "numeric",
      month: "short",
      day: "numeric",
    });
  } catch {
    return "";
  }
}

function Stars({ value, onChange }: { value: number; onChange?: (n: number) => void }) {
  return (
    <div className="stars" role="group" aria-label="별점">
      {[1, 2, 3, 4, 5].map((n) => (
        <button
          key={n}
          type="button"
          className={`star${n <= value ? " star-on" : ""}`}
          onClick={() => onChange?.(n)}
          disabled={!onChange}
          aria-label={`${n}점`}
        >
          ★
        </button>
      ))}
    </div>
  );
}

export function BookDetail({ bookId, user, onBack, onRead, onDeleted }: Props) {
  const [book, setBook] = useState<BookDetailData | null>(null);
  const [reviews, setReviews] = useState<Review[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Review form
  const [author, setAuthor] = useState("");
  const [rating, setRating] = useState(5);
  const [reviewText, setReviewText] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [deleting, setDeleting] = useState(false);

  async function handleDelete() {
    if (!book || !user) return;
    if (!window.confirm(`정말 "${book.title}"을(를) 삭제할까요?\n이 책의 음악 캐시, 리뷰까지 모두 사라집니다.`)) {
      return;
    }
    setDeleting(true);
    try {
      const res = await fetch(`/books/${bookId}`, {
        method: "DELETE",
        headers: { ...authHeaders(user) },
      });
      if (!res.ok && res.status !== 204) {
        const body = await res.text();
        throw new Error(body || `status ${res.status}`);
      }
      onDeleted();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setDeleting(false);
    }
  }

  async function loadAll() {
    try {
      const [b, r] = await Promise.all([
        fetch(`/books/${bookId}`).then((res) => {
          if (!res.ok) throw new Error(`book ${res.status}`);
          return res.json();
        }),
        fetch(`/books/${bookId}/reviews`).then((res) => res.ok ? res.json() : { reviews: [] }),
      ]);
      setBook(b);
      setReviews((r.reviews ?? []) as Review[]);
      setLoading(false);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setLoading(false);
    }
  }

  useEffect(() => {
    setLoading(true);
    setError(null);
    loadAll();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [bookId]);

  async function submitReview(e: React.FormEvent) {
    e.preventDefault();
    const trimmed = reviewText.trim();
    if (!trimmed) return;
    setSubmitting(true);
    try {
      const res = await fetch(`/books/${bookId}/reviews`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          author: author.trim() || null,
          rating,
          text: trimmed,
        }),
      });
      if (!res.ok) throw new Error(`status ${res.status}`);
      const newReview = (await res.json()) as Review;
      setReviews((prev) => [newReview, ...prev]);
      setReviewText("");
      setAuthor("");
      setRating(5);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSubmitting(false);
    }
  }

  const avgRating =
    reviews.length > 0
      ? reviews.reduce((a, r) => a + r.rating, 0) / reviews.length
      : 0;

  return (
    <section className="detail">
      <header className="library-top">
        <button className="reader-brand" onClick={onBack} title="라이브러리로">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <line x1="19" y1="12" x2="5" y2="12" />
            <polyline points="12 19 5 12 12 5" />
          </svg>
          <span>라이브러리</span>
        </button>
      </header>

      <div className="section section-narrow detail-content">
        {loading && (
          <div className="library-state">
            <div className="spinner" />
            <span>책 정보를 불러오는 중…</span>
          </div>
        )}

        {!loading && error && (
          <div className="library-state">
            <span className="error">{error}</span>
          </div>
        )}

        {!loading && book && (
          <>
            <div className="detail-head">
              <div className="detail-cover">
                <img
                  src={`/books/${book.id}/thumb${authQuery(user?.idToken)}`}
                  alt=""
                  loading="lazy"
                  onError={(e) => {
                    (e.currentTarget as HTMLImageElement).style.display = "none";
                  }}
                />
              </div>
              <div className="detail-meta">
                <div className="section-eyebrow">book detail</div>
                <h1 className="detail-title">{book.title}</h1>
                <div className="detail-info">
                  <span>{book.page_count}쪽</span>
                  <span>·</span>
                  <span>{formatDate(book.uploaded_at)}</span>
                  {reviews.length > 0 && (
                    <>
                      <span>·</span>
                      <span className="detail-rating">
                        ★ {avgRating.toFixed(1)} ({reviews.length})
                      </span>
                    </>
                  )}
                </div>
                <div className="detail-actions">
                  <button
                    className="cta"
                    onClick={() => onRead(book)}
                    disabled={book.audio_status !== "ready"}
                  >
                    {book.audio_status === "ready"
                      ? "읽기 시작"
                      : book.audio_status === "failed"
                      ? "음악 생성 실패 — 그래도 읽기"
                      : "음악 준비 중…"}
                  </button>
                  {book.audio_status !== "ready" && book.audio_status !== "failed" && (
                    <button className="cta-secondary" onClick={() => loadAll()}>
                      새로고침
                    </button>
                  )}
                  {user && book.uploader_email && user.email === book.uploader_email && (
                    <button
                      className="cta-danger"
                      onClick={handleDelete}
                      disabled={deleting}
                      type="button"
                    >
                      {deleting ? "삭제 중…" : "삭제"}
                    </button>
                  )}
                  <button
                    className="cta-secondary"
                    type="button"
                    onClick={async () => {
                      const url = `${location.origin}${location.pathname}#/book/${book.id}`;
                      try {
                        await navigator.clipboard.writeText(url);
                        alert("공유 링크가 복사됐어요");
                      } catch {
                        prompt("아래 링크를 복사하세요", url);
                      }
                    }}
                  >
                    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                      <circle cx="18" cy="5" r="3" />
                      <circle cx="6" cy="12" r="3" />
                      <circle cx="18" cy="19" r="3" />
                      <line x1="8.59" y1="13.51" x2="15.42" y2="17.49" />
                      <line x1="15.41" y1="6.51" x2="8.59" y2="10.49" />
                    </svg>
                    공유
                  </button>
                </div>
                {book.format === "epub" && (
                  <div className="detail-format-badge">EPUB · 텍스트 모드만 지원</div>
                )}
                {book.uploader_name && (
                  <div className="detail-uploader">
                    올린이 · <em>{book.uploader_name}</em>
                  </div>
                )}
              </div>
            </div>

            <section className="detail-block">
              <h2 className="detail-block-title">소개</h2>
              {book.description ? (
                <p className="detail-description">{book.description}</p>
              ) : (
                <p className="detail-description detail-description-empty">
                  아직 소개가 적히지 않은 책입니다.
                </p>
              )}
            </section>

            <section className="detail-block">
              <h2 className="detail-block-title">
                리뷰 <span className="detail-block-count">{reviews.length}</span>
              </h2>

              <form className="review-form" onSubmit={submitReview}>
                <div className="review-form-row">
                  <input
                    type="text"
                    placeholder="이름 (선택)"
                    value={author}
                    onChange={(e) => setAuthor(e.target.value)}
                    maxLength={40}
                    className="review-input"
                  />
                  <Stars value={rating} onChange={setRating} />
                </div>
                <textarea
                  placeholder="이 책 어땠어요?"
                  value={reviewText}
                  onChange={(e) => setReviewText(e.target.value)}
                  rows={3}
                  maxLength={2000}
                  className="review-textarea"
                />
                <div className="review-form-foot">
                  <span className="review-char-count">
                    {reviewText.length} / 2000
                  </span>
                  <button
                    type="submit"
                    className="cta cta-compact"
                    disabled={submitting || !reviewText.trim()}
                  >
                    {submitting ? "올리는 중…" : "리뷰 올리기"}
                  </button>
                </div>
              </form>

              {reviews.length === 0 && (
                <p className="review-empty">아직 리뷰가 없어요. 첫 리뷰의 주인공이 되어보세요!</p>
              )}

              <ul className="review-list">
                {reviews.map((r) => (
                  <li key={r.id} className="review-item">
                    <div className="review-head">
                      <span className="review-author">{r.author || "익명"}</span>
                      <Stars value={r.rating} />
                      <span className="review-date">{formatDate(r.created_at)}</span>
                    </div>
                    <p className="review-text">{r.text}</p>
                  </li>
                ))}
              </ul>
            </section>
          </>
        )}
      </div>
    </section>
  );
}
