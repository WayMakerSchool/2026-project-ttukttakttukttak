# 🔨 뚝딱뚝딱 (ttukttakttukttak)

> 팀 뚝딱뚝딱이 2026년에 만든 프로젝트 두 개, **책 배경음악**과 **바이브키 케어**를 모아 둔 저장소예요.

| 프로젝트 | 한 줄 소개 | 폴더 |
| :-- | :-- | :-- |
| 📚 책 배경음악 | PDF 책을 올리면 페이지의 분위기를 AI가 읽고, 어울리는 배경음악을 실시간으로 들려주는 웹앱 | [`book-bgm/`](book-bgm/) |
| 🔑 바이브키 케어 | 폰케이스 뒷면의 버튼 3개만 누르면 화면을 켜지 않아도 정해 둔 앱이 열리는 어르신용 기기 + 안드로이드 앱 | [`vibekey-care/`](vibekey-care/) |

<br>

## 📖 프로젝트 소개

- **기간**: 2026.05.18 ~ 2026.08.31
- **프로젝트**: 2026 웹 프로젝트(책 배경음악), 2026 임베디드 소프트웨어 경진대회 자유공모(바이브키 케어)
- **소개**
  - **책 배경음악**: 책을 읽을 때 장면에 맞는 음악이 흐르면 더 깊이 빠져들 수 있다는 생각에서 시작했어요. Gemini가 페이지마다 분위기(무드·BPM)를 분석하고, Lyria RealTime이 그에 맞는 음악을 실시간으로 만들어요.
  - **바이브키 케어**: 스마트폰 화면이 어려운 어르신도 전화·길 찾기·카카오톡을 바로 쓸 수 있도록 만들었어요. 케이스 뒷면의 물리 버튼(XIAO ESP32-S3)을 누르면 잠금 상태 그대로 앱이 열려요. 화면으로 확인하는 단계가 없는 기기라서, 깨진 신호가 잘못된 실행으로 이어지지 않도록 기기와 폰 사이의 신호를 직접 설계했어요.

<br>

## ✨ 주요 기능

### 📚 책 배경음악

| 기능 | 설명 |
| :-- | :-- |
| PDF 업로드 | 책을 올리면 페이지별 글을 뽑아 내 서재에 저장해요. |
| 분위기 분석 | Gemini가 페이지마다 무드, BPM, 음악 프롬프트를 분석해요. |
| 실시간 배경음악 | Lyria RealTime이 만든 음악을 WebSocket으로 받아 재생하고, 분위기가 크게 달라지면 크로스페이드로 전환해요. |
| 책 넘기기 뷰어 | 3D 책장 넘김과 여러 보기 방식, 목차 이동을 지원해요. |
| 읽어 주기(TTS) | 페이지 내용을 소리로 읽어 줘요. |
| 하이라이트 · 진행도 | 마음에 드는 문장을 표시하고, 읽던 위치를 기억해요. |
| 구글 로그인 | 로그인하면 즐겨찾기, 리뷰, 독서 통계가 계정에 저장돼요. |

### 🔑 바이브키 케어

| 기능 | 설명 |
| :-- | :-- |
| 버튼으로 앱 열기 | 1·2·3번 버튼을 누르면 잠금 화면에서도 정해 둔 앱이 열려요. 짧게/길게 누름을 구분해요. |
| AI 도우미 | 버튼을 길게 누르고 말로 물어보면 쉬운 한국어로 답하고 소리로 읽어 줘요. |
| 첫 실행 AI 키 매핑 | "자주 하는 일"을 고르면 AI가 설치된 앱을 보고 버튼에 배치하고 이유도 알려 줘요. |
| 어르신용 화면 | 큰 글씨, 고대비 색, 쉬운 말, 음성 안내와 진동을 함께 써요. |
| 삼성 루틴 연동 | 루틴에서 버튼 동작을 부르거나, 버튼 누름을 루틴 조건으로 쓸 수 있어요. |
| 신뢰할 수 있는 신호 | CRC16 + 순번(SEQ) + ACK + 재전송으로 깨진 신호는 실행하지 않아요. |
| 자가진단 | 기기 연결·권한·AI 호출·신호 품질 등 17가지를 한 번에 검사해요. |

<br>

## 🛠 기술 스택

### 📚 책 배경음악

- **언어**: Python, TypeScript
- **프레임워크 / 라이브러리**: FastAPI, PyMuPDF, google-genai (Gemini · Lyria RealTime), React, Vite, react-pdf, react-pageflip, Web Audio API
- **도구**: SQLite, Google 로그인

### 🔑 바이브키 케어

- **언어**: Java (안드로이드 앱), C++ (기기 펌웨어), Python (측정·발표자료 도구)
- **프레임워크 / 라이브러리**: Android SDK 34 (minSdk 24), AndroidX, Material Components, usb-serial-for-android, Gemini API, Arduino-ESP32 코어
- **도구**: Android Studio, Gradle, Arduino IDE, JUnit
- **하드웨어**: Seeed Studio XIAO ESP32-S3, 택트 스위치 3개, USB-C

<br>

## 👥 팀원

| <img src="https://github.com/jhan56936-ship-it.png" width="100"> | <img src="https://github.com/iceblue03.png" width="100"> | <img src="https://github.com/ghost.png" width="100"> |
| :--: | :--: | :--: |
| [Sean](https://github.com/jhan56936-ship-it) | [Andrew](https://github.com/iceblue03) | MJ |
| 책 배경음악 기획 · 개발<br>바이브키 케어 앱 · 펌웨어 개발 | 바이브키 케어 앱 개발 | 팀원 |

<br>

## ▶️ 실행 방법

```bash
# 1. 저장소 받기
git clone https://github.com/WayMakerSchool/2026-project-ttukttakttukttak.git

# 2. 폴더로 이동
cd 2026-project-ttukttakttukttak
```

### 📚 책 배경음악

```bash
# 백엔드
cd book-bgm/backend
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env   # .env 안에 GEMINI_API_KEY 채우기
uvicorn main:app --reload --port 8000

# 프론트엔드 (새 터미널에서)
cd book-bgm/frontend
npm install
npm run dev
```

브라우저에서 http://localhost:5173 을 열고 PDF를 올린 뒤 `음악 시작`을 눌러요.
자세한 내용은 [`book-bgm/README.md`](book-bgm/README.md)를 봐 주세요.

### 🔑 바이브키 케어

```bash
cd vibekey-care

# 앱 빌드 (JDK 17 필요)
./gradlew :app:assembleDebug

# 테스트 (기기 없이 실행 가능)
./gradlew :app:testDebugUnitTest
cd firmware/test && ./run_tests.sh
```

- `gradle.properties`의 `org.gradle.java.home`은 내 컴퓨터의 JDK 17 경로로 바꿔 주세요.
- 펌웨어는 Arduino IDE에서 `vibekey-care/firmware/vibekey_firmware/vibekey_firmware.ino`를 열고, 보드를 **XIAO_ESP32S3**, **USB CDC On Boot = Enabled**로 맞춰 업로드해요.
- 자세한 내용은 [`vibekey-care/README.md`](vibekey-care/README.md)와 [`vibekey-care/firmware/README.md`](vibekey-care/firmware/README.md)를 봐 주세요.

> ⚠️ Gemini API 키는 `.env`나 앱의 설정 화면에 넣고, 저장소에는 절대 올리지 마세요.

<br>

## 📁 폴더 구조

```
.
├── book-bgm/                  # 📚 책 배경음악
│   ├── backend/               # FastAPI 서버 (분위기 분석, 음악 스트림, TTS)
│   ├── frontend/              # React + Vite (뷰어, 오디오 플레이어, 서재)
│   └── README.md
├── vibekey-care/              # 🔑 바이브키 케어
│   ├── app/                   # 안드로이드 앱 (Java)
│   ├── firmware/              # XIAO ESP32-S3 펌웨어, 테스트, 측정 도구
│   ├── docs/                  # 발표자료, 영상 스토리보드
│   ├── videos/                # 광고·시연 영상 프로젝트
│   └── README.md
├── .gitignore                 # Git에 올리지 않을 파일 목록
└── README.md                  # 지금 보고 있는 문서
```

<br>

## 🤝 협업 규칙

- **브랜치**: `main` ← `feat/기능이름`, `fix/버그이름`
- **커밋 메시지**: `feat: 로그인 기능 추가`, `fix: 버튼 클릭 오류 수정`, `docs: README 수정`

| 타입 | 언제 쓰나요? |
| :-- | :-- |
| `feat` | 새로운 기능 추가 |
| `fix` | 버그 수정 |
| `style` | 디자인(CSS) 변경, 코드 동작에는 영향 없음 |
| `docs` | 문서(README 등) 수정 |
| `chore` | 설정 파일 등 기타 작업 |

> 💡 책 배경음악의 최신 작업(사진으로 책·목차 인식, 미리 만든 음악 라이브러리)은 [`feat/book-toc-photo-accuracy`](../../tree/feat/book-toc-photo-accuracy) 브랜치에 있어요.

<br>

## 🔗 원본 저장소

두 프로젝트의 커밋 기록을 그대로 가져왔어요.

- 책 배경음악: https://github.com/jhan56936-ship-it/project
- 바이브키 케어: https://github.com/jhan56936-ship-it/2026ESWContest_free_-ttukttakttukttak
