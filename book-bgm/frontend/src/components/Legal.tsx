import { useEffect, useState } from "react";

const CONTACT_EMAIL = "zipkyh@gmail.com";
const SERVICE_NAME = "BOOK STORE";

type Props = {
  onBack: () => void;
  initialSection?: string;
  /** When true, render in "first-visit consent" mode: hides the back button,
   *  shows a sticky accept bar at the bottom, and requires the user to
   *  scroll/accept before continuing. */
  requireConsent?: boolean;
  onConsent?: () => void;
};

export function Legal({ onBack, initialSection, requireConsent, onConsent }: Props) {
  const [agreedTos, setAgreedTos] = useState(false);
  const [agreedPrivacy, setAgreedPrivacy] = useState(false);
  const canProceed = agreedTos && agreedPrivacy;
  useEffect(() => {
    if (initialSection) {
      const el = document.getElementById(initialSection);
      if (el) el.scrollIntoView({ behavior: "smooth", block: "start" });
    } else {
      window.scrollTo({ top: 0 });
    }
  }, [initialSection]);

  return (
    <section className={`legal-page${requireConsent ? " legal-page-consent" : ""}`}>
      <header className="legal-header">
        {!requireConsent && (
          <button className="cam-back" onClick={onBack}>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <line x1="19" y1="12" x2="5" y2="12" />
              <polyline points="12 19 5 12 12 5" />
            </svg>
            돌아가기
          </button>
        )}
        <h1 className="legal-title">
          {requireConsent ? "시작하기 전에 — 이용약관 · 개인정보처리방침" : "이용약관 및 정책"}
        </h1>
        {!requireConsent && <span style={{ width: 80 }} />}
      </header>

      {requireConsent && (
        <div className="legal-consent-intro">
          BOOK STORE 를 처음 사용하시네요. 서비스를 시작하기 전에 아래
          이용약관과 개인정보처리방침을 읽고 동의해주세요. 한 번만 동의하시면
          다음부터는 곧장 시작 화면으로 들어갑니다.
        </div>
      )}

      <nav className="legal-nav">
        <a href="#tos">이용약관</a>
        <a href="#privacy">개인정보처리방침</a>
        <a href="#takedown">저작권 침해 신고</a>
      </nav>

      <article id="tos" className="legal-section">
        <h2>이용약관</h2>
        <p className="legal-meta">시행일: 2026-06-05</p>

        <h3>제1조 (목적)</h3>
        <p>
          본 약관은 {SERVICE_NAME}(이하 "서비스")의 이용과 관련하여 운영자와
          이용자의 권리·의무 및 책임사항을 규정함을 목적으로 합니다.
        </p>

        <h3>제2조 (정의)</h3>
        <ul>
          <li><strong>이용자</strong>: 본 서비스에 접속하여 자료를 업로드하거나 열람하는 모든 자.</li>
          <li><strong>콘텐츠</strong>: 이용자가 업로드한 PDF/EPUB 도서 파일, 표지 이미지, 메모, 하이라이트, 카메라 사진 등 일체의 데이터.</li>
          <li><strong>AI 생성물</strong>: 이용자가 업로드한 콘텐츠를 입력으로 Google Gemini·Lyria 등이 생성한 요약·이미지·음악 등.</li>
        </ul>

        <h3>제3조 (서비스의 제공)</h3>
        <p>
          서비스는 이용자가 업로드한 도서에 대해 AI 기반 요약, 챕터 일러스트레이션,
          분위기 음악, 단어 사전, 인물 관계도, 카메라 기반 챕터 인식 등을
          제공합니다. 운영자는 서비스의 안정성을 위해 예고 없이 일부 기능을
          수정·중단할 수 있습니다.
        </p>

        <h3 id="warranty">제4조 (콘텐츠에 대한 이용자의 보증) — 가장 중요</h3>
        <p>
          이용자는 도서 또는 표지 이미지를 업로드함에 있어 <strong>다음 중 어느
          하나에 해당한다는 사실을 보증</strong>합니다:
        </p>
        <ol>
          <li>해당 저작물의 저작권을 본인이 보유하거나,</li>
          <li>저작권자로부터 명시적 이용 허락(서면 라이선스 포함)을 받았거나,</li>
          <li>저작권법상 보호기간이 만료되었거나(PD: Public Domain),</li>
          <li>저작권법 제30조(사적이용을 위한 복제) 등 권리제한 사유에 해당하여 본 서비스에서의 처리가 적법한 경우.</li>
        </ol>
        <p>
          이용자가 위 보증을 위반하여 제3자의 저작권 등 권리를 침해함에 따라
          발생한 손해(소송비용, 화해금, 손해배상 등 포함)에 대하여는 이용자
          본인이 모든 책임을 부담하며, 운영자는 이에 대하여 어떠한 책임도
          지지 않습니다.
        </p>
        <p>
          업로드 시점에 본 보증의 동의 일시가 서버에 기록되며, 분쟁 발생 시
          증거로 사용될 수 있습니다.
        </p>

        <h3>제5조 (공개 범위)</h3>
        <p>
          업로드된 도서는 <strong>기본값이 비공개(private)</strong>이며, 업로더
          본인만 열람할 수 있습니다. 이용자가 명시적으로 공개(public)로 변경한
          경우에 한하여 다른 이용자가 열람할 수 있습니다. 공개 설정 시에도
          제4조의 보증은 그대로 유효합니다.
        </p>

        <h3>제6조 (AI 처리에 대한 동의)</h3>
        <p>
          이용자는 본 서비스가 업로드된 도서의 텍스트·이미지를 Google LLC 의
          Gemini·Lyria 등 외부 AI API 로 전송하여 분석·생성에 사용하는 것에
          동의합니다. 운영자는 Google 의 데이터 처리 정책상 입력값이 모델
          학습에 사용되지 않음을 신뢰하나, 외부 API 의 정책 변경에 따른 위험은
          이용자가 알고 있음을 전제로 합니다.
        </p>

        <h3>제7조 (AI 생성물의 권리)</h3>
        <p>
          AI 생성물의 권리는 원저작물의 권리에 종속될 수 있으며, 이용자는 AI
          생성물이 원저작물의 2차적저작물에 해당할 가능성을 인정하고 본 서비스
          외부에 배포하기 전 별도의 권리 검토를 수행할 책임이 있습니다.
        </p>

        <h3>제8조 (금지행위)</h3>
        <ul>
          <li>제3자의 저작권·초상권·개인정보를 침해하는 콘텐츠의 업로드</li>
          <li>음란·폭력·차별·증오 콘텐츠의 업로드</li>
          <li>악성코드 삽입, API 남용, 다량 자동화 요청 등 서비스 방해 행위</li>
          <li>본 약관 제4조 보증에 반하는 콘텐츠의 공개 설정</li>
        </ul>

        <h3>제9조 (책임의 제한)</h3>
        <p>
          본 서비스는 무상으로 제공되며, 운영자는 서비스의 무중단·무오류를
          보장하지 않습니다. 외부 API(특히 Google Gemini/Lyria) 장애로 인한
          기능 미작동, AI 생성물의 부정확성, 저작권 분쟁 등에 대해 운영자는
          관계법령상 허용되는 최대한의 범위에서 책임을 지지 않습니다.
        </p>

        <h3>제10조 (분쟁 해결 및 준거법)</h3>
        <p>
          본 약관과 관련하여 분쟁이 발생한 경우 대한민국 법령을 준거법으로
          하며, 관할법원은 민사소송법상의 일반 관할에 따릅니다.
        </p>
      </article>

      <article id="privacy" className="legal-section">
        <h2>개인정보처리방침</h2>
        <p className="legal-meta">시행일: 2026-06-05</p>

        <h3>1. 수집하는 개인정보 항목</h3>
        <ul>
          <li>
            <strong>Google 로그인 시</strong>: 이메일, 이름, 프로필 사진 URL,
            Google 발급 식별자(sub). Google ID 토큰을 그대로 보관하지 않으며,
            요청 시 서명 검증 후 클레임만 메모리 캐시에 60초간 유지합니다.
          </li>
          <li>
            <strong>비로그인 이용 시</strong>: 브라우저 localStorage에 무작위로
            생성된 익명 클라이언트 ID(UUID v4). 다른 식별자는 수집하지 않습니다.
          </li>
          <li>
            <strong>업로드 콘텐츠</strong>: 도서 파일(PDF/EPUB), 표지 이미지,
            도서 메타데이터(저자/출판사/태그 등), 하이라이트·메모,
            카메라로 촬영한 표지/페이지 사진.
          </li>
          <li>
            <strong>이용 기록</strong>: 마지막으로 읽은 페이지, 읽은 시간(초),
            요청 IP(서버 로그, 최대 14일).
          </li>
        </ul>

        <h3>2. 처리 목적</h3>
        <ul>
          <li>본인 라이브러리 식별 및 접근 권한 관리</li>
          <li>AI 기반 요약·음악·이미지 생성 및 캐싱</li>
          <li>읽기 진도 동기화, 통계 표시</li>
          <li>오남용·악성 요청 차단 (Rate Limiting)</li>
        </ul>

        <h3>3. 보관 및 파기</h3>
        <ul>
          <li>도서/메타데이터: 이용자가 삭제 요청 시 즉시 파기.</li>
          <li>카메라 사진: 챕터 인식 처리 후 메모리에서만 사용하며 서버에 저장하지 않습니다. (보드 카메라의 경우 외부 voicegared 서버의 임시 저장은 해당 서버 정책에 따릅니다.)</li>
          <li>서버 액세스 로그: 최대 14일 후 자동 삭제.</li>
          <li>탈퇴(라이브러리 전체 삭제) 요청: 아래 이메일로 요청.</li>
        </ul>

        <h3>4. 제3자 제공·위탁</h3>
        <ul>
          <li>
            <strong>Google LLC</strong>: 도서 텍스트·이미지 일부를 Gemini API,
            Lyria API 로 전송하여 AI 처리. 송신 데이터는 Google 의 API 데이터
            처리 정책에 따라 모델 학습에 사용되지 않습니다.
          </li>
          <li>
            <strong>Google Identity Services</strong>: 로그인 시 토큰 발급
            절차에 한해 처리.
          </li>
          <li>이 외 별도의 제3자에게 개인정보를 제공·판매하지 않습니다.</li>
        </ul>

        <h3>5. 이용자의 권리</h3>
        <p>
          이용자는 언제든지 자신의 개인정보를 열람·정정·삭제·처리정지를 요청할
          수 있으며, 아래 이메일을 통해 처리됩니다. 요청 후 영업일 기준 7일
          이내에 회신합니다.
        </p>

        <h3>6. 안전성 확보 조치</h3>
        <ul>
          <li>책 식별자(book_id) 길이·형식 검증 및 권한 미들웨어 적용</li>
          <li>CSP, X-Frame-Options, Referrer-Policy 등 보안 헤더</li>
          <li>요청자 단위 슬라이딩 윈도우 Rate Limit</li>
          <li>업로드 최대 200MB 제한, 표지 이미지 MIME 검증</li>
        </ul>

        <h3>7. 개인정보 보호책임자</h3>
        <p>
          연락처: <a href={`mailto:${CONTACT_EMAIL}`}>{CONTACT_EMAIL}</a>
        </p>
      </article>

      <article id="takedown" className="legal-section">
        <h2>저작권 침해 신고</h2>

        <p>
          본 서비스에 게시된 콘텐츠가 귀하의 저작권 또는 기타 권리를 침해한다고
          판단하시는 경우, 아래 양식으로 이메일을 보내주시면 접수 후{" "}
          <strong>영업일 기준 24~48시간 이내</strong>에 검토하여 조치하고
          회신해드립니다. (한국 저작권법 제103조에 따른 절차)
        </p>

        <h3>신고 시 포함 정보</h3>
        <ol>
          <li>침해당했다고 주장하시는 저작물의 정보 (제목, 저자, 출판사, ISBN)</li>
          <li>본 서비스 내 침해 게시물의 URL 또는 책 ID</li>
          <li>신고인의 성명, 연락처, 권리관계 (저자 본인 / 출판사 담당자 / 대리인)</li>
          <li>본 신고 내용이 사실임을 확인하는 진술 (위증 시 신고인이 책임)</li>
          <li>증빙: 저작권 보유 증명(출판계약서 발췌, 권리등록증 등)</li>
        </ol>

        <h3>접수 채널</h3>
        <p style={{ fontSize: 16 }}>
          이메일:{" "}
          <a
            href={`mailto:${CONTACT_EMAIL}?subject=${encodeURIComponent("[BOOK STORE] 저작권 침해 신고")}`}
          >
            {CONTACT_EMAIL}
          </a>
        </p>
        <p>제목 머리말: <code>[BOOK STORE] 저작권 침해 신고 — 도서명</code></p>

        <h3>처리 절차</h3>
        <ol>
          <li>접수 후 24시간 이내 자동 접수 확인 회신</li>
          <li>48시간 이내 1차 검토 및 게시중단 (필요 시 즉시 비공개 처리)</li>
          <li>업로더에게 통지하고 7일간 이의제기 기회 부여</li>
          <li>이의제기가 없으면 영구 삭제, 이의제기 있으면 분쟁 절차</li>
        </ol>

        <h3>허위신고에 대한 면책</h3>
        <p>
          신고인이 고의 또는 중과실로 허위의 신고를 한 경우, 그로 인하여 발생한
          업로더 및 운영자의 손해에 대하여 신고인이 책임을 부담합니다.
        </p>
      </article>

      <footer className="legal-footer">
        문의: <a href={`mailto:${CONTACT_EMAIL}`}>{CONTACT_EMAIL}</a>
      </footer>

      {requireConsent && (
        <div className="legal-consent-bar">
          <div className="legal-consent-checks">
            <label className="legal-consent-check">
              <input
                type="checkbox"
                checked={agreedTos}
                onChange={(e) => setAgreedTos(e.target.checked)}
              />
              <span>이용약관에 동의합니다</span>
            </label>
            <label className="legal-consent-check">
              <input
                type="checkbox"
                checked={agreedPrivacy}
                onChange={(e) => setAgreedPrivacy(e.target.checked)}
              />
              <span>개인정보처리방침에 동의합니다</span>
            </label>
          </div>
          <button
            type="button"
            className="legal-consent-go"
            disabled={!canProceed}
            onClick={() => onConsent?.()}
          >
            동의하고 시작
          </button>
        </div>
      )}
    </section>
  );
}
