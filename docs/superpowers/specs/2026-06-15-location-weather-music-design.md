# 위치·날씨 기반 음악 10% 튜닝 — 설계 문서

작성일: 2026-06-15
브랜치: `location-weather-music`

## 목표

독자가 책을 읽는 **실제 환경**(GPS로 추정한 장소 종류 + 그 지역 날씨 + 낮/밤 + 계절)을
배경음악에 **아주 살짝(약 10%)** 반영한다. 예: 카페에서 읽으면 카페 느낌을, 비 오는 밤이면
비·밤 느낌을 음악에 은은하게 입힌다. 책 분위기(90%)가 여전히 음악을 지배하며, 환경은 양념 수준이다.

핵심 제약(기존 프로젝트 원칙 유지):
- **저비용**: 외부 AI 호출 추가 금지. 날씨/장소→키워드는 전부 **규칙 기반 매핑**(AI 0회).
- 외부 데이터는 **무료·키 불필요** 서비스만 사용.
- 실패 시 **기존 동작과 100% 동일**하게 폴백(막힘·고장 없음).

## 핵심 아이디어

Lyria RealTime는 `set_weighted_prompts`로 **여러 개의 가중치 프롬프트**를 받는다.
현재 코드는 "책 분위기" 프롬프트 하나만 weight 1.0으로 보낸다
(`music_streamer.py:51` `set_weighted_prompts(prompts=[WeightedPrompt(text=prompt, weight=1.0)])`).

→ 여기에 **환경 보조 프롬프트를 weight 0.1로 추가**하고 책 프롬프트를 weight 0.9로 낮춘다.
Lyria가 가중치를 내부적으로 정규화하므로 0.9 : 0.1 = 책 90% : 환경 10%가 된다.

## 환경 컨텍스트 구성 (10% 안에 모두 포함)

GPS 좌표(위도/경도)와 현재 시각으로부터 다음을 만든다:

| 신호 | 출처 | 매핑 예시 |
|---|---|---|
| 장소 종류 | OpenStreetMap (Overpass/Nominatim, 무료·키 불필요) | `cafe`→"cozy cafe ambience, warm soft chatter", `library`→"hushed quiet study calm", `park`→"open-air natural lightness", `beach`→"seaside breeze" |
| 날씨 | Open-Meteo (무료·키 불필요) | WMO weather_code → "rainy", "clear bright", "overcast", "snowy" 등 |
| 낮/밤 | Open-Meteo `is_day` | 1→"daytime", 0→"nighttime" |
| 계절 | 현재 월 + 반구(위도 부호) | 북반구 3~5월=spring … 남반구는 6개월 시프트 |

→ 이 신호들을 합쳐 **짧은 영어 한 줄**로 만든다.
예: `"cozy cafe ambience, rainy overcast, nighttime, autumn"` (weight 0.1).

장소를 못 찾으면 장소 부분만 생략, 날씨 실패면 날씨 부분만 생략. 전부 실패면 컨텍스트는 `None`.

## 조회 빈도

**책/세션당 1회.** 캐시 세그먼트(PCM)가 업로드/세션 생성 시점에 미리 생성되는 구조이므로,
환경 컨텍스트도 그 시점에 1회 조회하여 그 책(또는 카메라 세션)의 모든 세그먼트에 동일하게 적용한다.
실시간 추적/재조회는 하지 않는다(저비용 + "아주 살짝"이라는 의도에 부합).

## 컴포넌트 / 변경 지점

### 신규: `backend/place_weather.py`
- `async def location_music_context(lat: float, lon: float) -> str | None`
  - Overpass/Nominatim로 최근접 장소 카테고리 조회 (타임아웃 ~3s, User-Agent 헤더 포함)
  - Open-Meteo `current` 호출로 weather_code·is_day·local time 조회 (타임아웃 ~3s)
  - 월+반구로 계절 계산
  - 위 신호를 키워드로 매핑·결합해 한 줄 영어 프롬프트 반환, 실패 시 `None`
  - 좌표는 호출 전 소수점 ~2자리로 반올림(프라이버시 + 캐시 효율)
- 순수 매핑 함수들(테스트 대상): `weather_code_to_words`, `season_for(month, lat)`, `place_category_to_words`

### `backend/music_streamer.py`
- `set_prompt(self, prompt_text, bpm=90, context_prompt: str | None = None, context_weight: float = 0.1)`
  - `context_prompt`가 있으면 `[WeightedPrompt(book, 1.0 - context_weight), WeightedPrompt(context, context_weight)]`
  - 없으면 기존대로 단일 프롬프트 (하위 호환)

### `backend/audio_cache.py`
- `capture_lyria_pcm(prompt, bpm, duration_s, context_prompt=None)` — `set_prompt`에 전달
- `generate_all_segments(book_id, moods, context_prompt=None)` — 각 세그먼트 캡처에 전달

### `backend/camera_book.py`
- 카메라 세그먼트 생성부(`capture_lyria_pcm` 호출, `camera_book.py:1082`)에 `context_prompt` 전달
- 카메라 오디오 백그라운드 생성 경로에 컨텍스트 흘려보냄

### `backend/main.py`
- Permissions-Policy 헤더: `geolocation=()` → `geolocation=(self)` (`main.py:169`)
- 책 업로드 엔드포인트: optional `lat`, `lon` Form 필드 추가 → `location_music_context` 호출 → `generate_all_segments`에 전달
- `POST /camera/sessions`: 요청 본문에 optional `lat`, `lon` 추가 → 동일하게 컨텍스트 생성·전달
- 원시 좌표는 영구 저장/로그하지 않음

### 프론트엔드
- 책 업로드 / 카메라 세션 시작 시 `navigator.geolocation.getCurrentPosition()` 호출
  - 성공: `{lat, lon}`을 기존 요청에 포함
  - 거부/타임아웃/미지원: 좌표 없이 진행(보조 프롬프트 없음)
- 권한 요청 실패가 업로드/세션 시작을 막지 않도록 처리

## 에러 처리 / 폴백

- GPS 거부·미지원·타임아웃 → 좌표 미전송 → 컨텍스트 `None` → 기존 동작 동일
- Overpass/Nominatim 또는 Open-Meteo 실패·타임아웃 → 해당 부분 생략 또는 전체 `None`
- 외부 호출은 모두 짧은 타임아웃 + try/except로 감싸 음악 생성 본류를 절대 막지 않음

## 테스트

- 순수 매핑 함수 단위 테스트: `weather_code_to_words`(대표 WMO 코드들), `season_for`(북/남반구·경계 월), `place_category_to_words`
- `set_prompt`가 `context_prompt` 유무에 따라 1개/2개 가중치 프롬프트를 올바른 weight(0.9/0.1)로 구성하는지 (Lyria 세션 목)
- `location_music_context`: 외부 HTTP를 목으로 대체해 결합 문자열·실패 폴백(`None`) 검증

## 비목표 (YAGNI)

- 실시간 위치/날씨 추적, 페이지별 재조회
- 유료 Places/Geocoding API
- 사용자가 장소를 수동 선택하는 UI(이번 범위 아님)
- 환경에 따른 BPM/세기 변경 — 이번엔 가중치 프롬프트 텍스트에만 반영(필요 시 후속)
