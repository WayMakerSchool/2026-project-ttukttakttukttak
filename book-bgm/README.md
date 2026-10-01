# 책 배경음악 (Book Background Music)

PDF 책을 업로드하면 페이지마다 Gemini가 분위기를 분석하고, 그에 맞춰
**Lyria RealTime**이 실시간으로 배경음악을 생성·전환해주는 웹앱.

## 구조

```
backend/   FastAPI + PyMuPDF + google-genai (Gemini + Lyria RealTime)
frontend/  Vite + React + react-pdf + Web Audio API
```

데이터 흐름:

1. 사용자가 PDF 업로드 → 백엔드가 페이지별 텍스트 추출
2. Gemini 2.5 Flash로 페이지마다 무드(`prompt`, `bpm`, `mood`, …)를 한 번에 분석·캐시
3. 프론트가 음악 시작 → 백엔드가 Lyria RealTime 세션을 열어 PCM을 WebSocket으로 스트림
4. 사용자가 페이지를 넘기면 무드 거리를 계산, 임계값을 넘으면 Lyria 프롬프트를 교체 → 자연스럽게 크로스페이드

## 실행 방법

### 1) Gemini API 키 발급
- https://aistudio.google.com/apikey 에서 발급
- Lyria RealTime은 프리뷰/실험 단계라 별도 액세스가 필요할 수 있음

### 2) 백엔드
```bash
cd backend
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env   # 그리고 .env 안에 GEMINI_API_KEY 채우기
uvicorn main:app --reload --port 8000
```

### 3) 프론트엔드
```bash
cd frontend
npm install
npm run dev
```

브라우저에서 http://localhost:5173 열기 → PDF 업로드 → "음악 시작" 클릭 → 페이지 넘기기.

## 주의사항

- **Lyria RealTime은 실험 단계**입니다. API surface, 모델명(`models/lyria-realtime-exp`),
  세션 길이 제한, 출력 포맷(현재 48 kHz 스테레오 PCM 가정)이 바뀔 수 있어
  `backend/music_streamer.py`와 `frontend/src/components/AudioPlayer.tsx`의
  샘플레이트·채널 수는 실제 응답을 보고 조정해야 할 수 있습니다.
- PDF에서 텍스트 추출이 안 되는 (스캔 이미지 기반) 책은 OCR이 따로 필요합니다.
- 현재 책 캐시는 인메모리(`BOOKS` dict)라 서버 재시작 시 사라집니다.
  운영용으로 가려면 Redis나 SQLite로 옮기세요.

## 다음에 손볼 만한 것들

- [ ] 스무딩 — 인접 N페이지 무드 평균으로 떨림 줄이기
- [ ] 책 캐시를 Redis/SQLite로 영속화
- [ ] OCR 폴백 (스캔 PDF 지원)
- [ ] 무드 전환 시 가중치 보간으로 더 부드러운 크로스페이드
- [ ] 사용자가 분위기를 직접 조정할 수 있는 슬라이더
