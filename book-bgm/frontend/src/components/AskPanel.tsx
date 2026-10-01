import { useEffect, useRef, useState } from "react";

interface ChatMessage {
  role: "user" | "assistant";
  content: string;
}

interface Props {
  bookId: string;
  open: boolean;
  page: number;
  onClose: () => void;
}

const STARTERS = [
  "지금까지 줄거리를 짧게 요약해줘",
  "이 페이지에서 중요한 게 뭐야?",
  "주요 인물 관계를 정리해줘",
  "이 책의 주제는 뭐야?",
];

export function AskPanel({ bookId, open, page, onClose }: Props) {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [loading, setLoading] = useState(false);
  const bodyRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  // Reset history per book.
  useEffect(() => {
    setMessages([]);
    setInput("");
  }, [bookId]);

  // Auto-scroll to bottom on new messages.
  useEffect(() => {
    const el = bodyRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messages, loading]);

  async function send(text: string) {
    const q = text.trim();
    if (!q || loading) return;
    const history = messages;
    setMessages([...history, { role: "user", content: q }]);
    setInput("");
    setLoading(true);
    try {
      const res = await fetch(`/books/${bookId}/ask`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          question: q,
          page,
          history,
        }),
      });
      if (!res.ok) {
        const body = await res.text();
        throw new Error(body || `status ${res.status}`);
      }
      const data = await res.json();
      setMessages((prev) => [
        ...prev,
        { role: "assistant", content: data.answer || "(빈 응답)" },
      ]);
    } catch (e) {
      setMessages((prev) => [
        ...prev,
        {
          role: "assistant",
          content: `⚠ ${e instanceof Error ? e.message : String(e)}`,
        },
      ]);
    } finally {
      setLoading(false);
    }
  }

  function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    send(input);
  }

  if (!open) return null;

  return (
    <>
      <div className="toc-backdrop" onClick={onClose} aria-hidden="true" />
      <aside className="toc-drawer ask-drawer" role="dialog" aria-label="책에게 묻기">
        <header className="toc-header">
          <h2>책에게 묻기</h2>
          <button className="toc-close" onClick={onClose} aria-label="닫기" type="button">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <line x1="18" y1="6" x2="6" y2="18" />
              <line x1="6" y1="6" x2="18" y2="18" />
            </svg>
          </button>
        </header>

        <div className="ask-body" ref={bodyRef}>
          {messages.length === 0 && !loading && (
            <div className="ask-empty">
              <div className="ask-empty-title">
                지금 보고 있는 페이지를 컨텍스트로 답해드려요.
              </div>
              <div className="ask-starters">
                {STARTERS.map((s) => (
                  <button
                    key={s}
                    type="button"
                    className="ask-starter"
                    onClick={() => send(s)}
                    disabled={loading}
                  >
                    {s}
                  </button>
                ))}
              </div>
            </div>
          )}
          {messages.map((m, i) => (
            <div key={i} className={`ask-msg ask-msg-${m.role}`}>
              <div className="ask-msg-bubble">{m.content}</div>
            </div>
          ))}
          {loading && (
            <div className="ask-msg ask-msg-assistant">
              <div className="ask-msg-bubble ask-msg-loading">
                <span className="spinner-sm" /> 생각 중…
              </div>
            </div>
          )}
        </div>

        <form className="ask-form" onSubmit={onSubmit}>
          <textarea
            className="ask-input"
            placeholder="이 책에 대해 물어보세요…"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                send(input);
              }
            }}
            rows={2}
            disabled={loading}
            maxLength={2000}
          />
          <button
            type="submit"
            className="cta cta-compact"
            disabled={loading || !input.trim()}
          >
            보내기
          </button>
        </form>
      </aside>
    </>
  );
}
