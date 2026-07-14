# 사진으로 목차 찾기 — 정확도 대폭 개선 설계

날짜: 2026-07-14 · 대상: 카메라 리더 (backend/camera_book.py, backend/main.py, frontend/src/components/CameraReader.tsx)

## 배경

현재 흐름: 표지 사진 → `/camera/identify` (Gemini vision 1회: 표지 판독 + AI 지식 기반 목차 생성)
→ 프론트가 백그라운드로 `/camera/toc-lookup` 호출 → Yes24 크롤 성공 시 실제 인쇄 목차로 자동 교체.

남은 정확도 병목:
1. Yes24가 못 찾는 책(구간본·외서·독립출판·그림책)은 AI 추측 목차에 머무름.
2. 판본 특정 실패 — 표지 프롬프트가 ISBN을 읽으라고 지시하지만 응답 스키마에 `isbn`
   필드가 없어 결과가 버려짐. ISBN만 있으면 서점에서 판본을 정확히 특정 가능.

## 결정 사항

### ① 목차 페이지 직접 촬영 (신규)

- 새 엔드포인트 `POST /camera/toc-from-photo` (multipart: `photo`, `source`, `book_name`).
- `extract_toc_from_photo()`: Gemini vision 1회 호출로 인쇄된 목차를 **그대로**(verbatim)
  추출 + 챕터별 음악 메타데이터(summary/music_prompt/bpm/mood) 동시 생성.
  - 기존 `_enhance_for_vision` 전처리 재사용 (회전 보정·선명화).
  - 목차 페이지가 아니면 `is_toc_page: false` + 한국어 사유 반환.
  - 소주제 나열 줄은 별도 챕터가 아니라 직전 챕터의 summary로 접음
    (기존 `_parse_scraped_toc_locally` 규칙과 동일).
- 다중 페이지 목차: 프론트가 페이지별 결과를 이어붙임. 정규화된 제목 기준 중복 제거,
  idx 재부여. 서버는 무상태 유지.
- 우선순위: **사진 목차 > 서점 크롤 목차 > AI 추측 목차.**
  프론트 `tocSource` 상태('llm'|'store'|'photo')로 관리 — 사진으로 확정한 목차는
  백그라운드 Yes24 자동 업그레이드가 덮어쓰지 못함.
- 세션 생성 시 목차 고정(lock-in) 규칙은 기존 그대로 (TOC는 클라이언트가 준 것을
  verbatim 채택, 재생성 금지).

### ② 자동 업그레이드 적중률 강화

- `COVER_PROMPT` 스키마에 `isbn` 추가(바코드/판권면에서 판독, 숫자만). 백엔드에서
  10/13자리 검증 후 `/camera/identify` 응답에 포함.
- `TocLookupIn`에 `isbn` 필드 추가. `_find_yes24_ids`는 ISBN이 있으면 **ISBN 검색을
  1순위**로 실행 — 적중 시 다른 쿼리로 희석하지 않고 즉시 사용. ISBN으로 찾은
  후보는 제목 하드 게이트를 우회(표기 차이로 인한 오탈락 방지)하고 최고 가점.
- 응답에 `verbatim: bool` 추가 — 프론트 자동 업그레이드는 verbatim일 때만 교체.

### 조사 결과에 따른 범위 조정 (원안 대비)

원안의 "알라딘 크롤러(HTML 스크래핑) 추가"는 **제외**:
- 알라딘/교보 모두 목차가 상품 페이지에 서버 렌더링되지 않음 (JS ajax 로드, 재확인).
- 교보문고: 302KB 응답하지만 목차는 별도 비동기 API — 초기 HTML에 없음.

## v2 (2026-07-14) — 정확도 한 단계 더

3-소스 기반 위에서 다음을 추가:

### A. 사진 목차 2-pass 자기검증 (Gemini만, 키 불필요)
- `extract_toc_from_photo`: 1차 전사 프롬프트에 `confidence` + 자기검증 지시 추가.
- 1차 `confidence < 0.9`일 때만 `_verify_toc_from_photo`(lite 모델)로 같은
  이미지를 재판독 — 놓친 줄 추가, 오독 글자 교정, 순서 교정. 평상시엔 1콜 유지
  ([[feedback_cheap_simple]] 존중). 교정 리스트가 draft 절반 미만이면 무시(안전장치).
- 라이브 검증: 일부러 1항목 뺀 draft → 실제 호출로 누락 항목 복원 + 오탈자 교정 확인.

### B. 알라딘 공식 TTB API 소스 (옵션, 도먼트)
- `crawl_toc_aladin_api(isbn)`: `ItemLookUp&OptResult=Toc`의 `subInfo.toc`를
  `_strip_html` → 기존 로컬 파서로 처리(제목 verbatim, 모델 생성 아님).
- `ALADIN_TTB_KEY` env 있을 때만 활성. 무료 키:
  https://www.aladin.co.kr/ttb/wblog_manage.aspx
- 파서는 네트워크 mock 단위테스트로 검증.

### C. 소스 교차검증 (`reconcile_tocs`, 호출 비용 0)
- 신뢰 내림차순 후보 리스트를 받아 최상위 verbatim 소스의 제목을 채택,
  나머지는 summary만 보완. 승자와 차상위 소스의 제목 겹침으로 합의 문구 생성
  ("알라딘과 Yes24가 20장 일치 ✅" / "불일치 — 더 정확한 X 기준 사용").
- `/camera/toc-lookup`의 `_do_crawl`이 알라딘·Yes24를 병렬 수집 후 이 함수로
  승자 선택 + 합의 문구를 `matched_edition`에 실어 UI에 노출.
- Yes24 후보 폭 4→6으로 확대(동시 fetch라 지연 동일).

### 검증 결과 (v2)
- 단위 테스트 18개 통과 (reconcile 6, aladin-parse 3, isbn 6, parser 3 등).
- 라이브: `/camera/toc-lookup` 총균쇠 → Yes24 실제 목차 34장 정확 반환.
  2-pass verify → 누락/오탈자 복원 확인. 프론트 tsc 통과.

## v2.1 (2026-07-14) — 챕터 인식(목차 인식) 정확도

읽는 중인 페이지 → 어느 챕터인지 인식하는 라이브 감지(`detect_chapter_from_image`)
개선. 핵심 실패 모드: 모델이 헤딩을 정확히 OCR해도 30+ 항목 목차에서 **인덱스를
잘못 계산**함.

- **결정론적 매칭 레이어**: DETECT_PROMPT가 이제 읽은 근거를 구조화해 반환
  (`heading_text`, `chapter_number`, `heading_kind`, `running_header`,
  `page_number`, `signal`). 인덱스 선택은 파이썬 `resolve_detection`이 담당:
  1. verbatim 헤딩 제목 → `_match_by_title_text` (정규화 완전일치 → 포함),
  2. 장/부 번호 → `_extract_chapter_key`/`_match_by_chapter_key`
     (제1부 vs 제1장 교차매칭 방지),
  3. 러닝헤더 제목, 4. 없으면 모델 인덱스(본문 추론).
- 결정론 매칭 성공 시 신뢰도를 올려 `decide_chapter`가 **먼 챕터 점프도 허용**
  (인쇄된 헤딩은 거리와 무관하게 확정 증거). 본문 추론만일 때는 기존 인접-only
  게이트로 지터 방지.
- 검증: 단위 12개(경계·교차매칭·게이트) + 라이브 — 2장에 있던 상태에서 "제3장"
  페이지 촬영 → matched_by=heading_text, idx 4로 정확 점프. 백엔드 총 49개 통과.

## 오류 처리

- Gemini 쿼터/키 만료 매핑은 identify와 동일한 한국어 안내 재사용 (헬퍼로 공용화).
- 사진이 목차 페이지가 아닐 때: `toc_error`에 모델이 준 사유("본문 페이지로 보여요" 등).
- 크롤/vision 실패 시 기존 폴백 체계 유지 (LLM 목차 → 최소한의 결과 보장).

## 검증 계획

- 단위: `_clean_isbn` 정규화, 기존 파서 회귀 (backend/.venv pytest).
- E2E: 로컬 uvicorn 기동 → PIL로 합성한 목차 페이지 이미지를
  `/camera/toc-from-photo`에 POST → verbatim 추출 확인.
  `/camera/toc-lookup`에 ISBN 포함 POST → Yes24 정확 판본 적중 확인.
- 프론트: tsc/vite build 통과 확인.

## 실행 위치

`~/dev/프로젝트` (로컬). Desktop/프로젝트는 Google Drive 스트리밍 폴더라 실행 불가
(2026-07-14 git status 2분 타임아웃 재확인). GitHub 원격에는 카메라 기능 브랜치가
없어 Desktop 작업본을 rsync로 로컬 클론 위에 복원 후 작업.
