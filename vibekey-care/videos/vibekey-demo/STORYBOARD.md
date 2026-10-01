---
format: 1920x1080
duration: 175s
message: "어르신도 버튼 세 번이면 끝"
arc: "벽 → 버튼 → 실제로 열린다 → 안을 열면 → 증명 → 되돌아오기"
audience: "2026 임베디드SW 경진대회 자유공모 심사위원"
mode: autonomous
music: none
---

# 바이브키 시연영상

내레이션 없음. **화면 글자가 설명을 전부 진다.** 따라서 각 씬의 등장은
말이 아니라 **읽는 속도**에 맞춘다 — 한 줄이 들어오고, 눈이 그것을 읽을
시간을 준 뒤, 다음 조각이 들어온다.

## Video direction

- **팔레트** — `frame.md` 그대로. 캔버스 `#F7F9FC`, 잉크 `#101418`, 유일한 강조색
  코발트 `#0B57D0`. 버튼 구분이 필요한 곳에서만 초록 `#12703A` · 호박 `#B26A00`을
  쓰고, 실패·손상 표시에만 적색 `#B3261E`을 쓴다. 그 외 색은 없다.
- **타입** — display/숫자/크롬은 Gothic A1, 본문은 Noto Sans KR. `frame.md`의
  역할 이름으로만 부른다.
- **모션 문법** — 기본 이징 `power3`, 길게 빠지는 곡선. 튀는 것보다 매끄러운 것.
  **읽기 페이싱 모델**: t=0에는 그 순간 읽어야 할 것만 있고, 나머지는 읽을
  차례가 왔을 때 들어온다. 뒤쪽 50%에 반드시 새 등장이 남아 있어야 한다.
- **정지 배분** — 프레임 4(짧게·길게)와 프레임 8(워치독)은 **의도적으로 정지**해
  읽게 두는 호흡 프레임. 프레임 9(숫자) 직전에 프레임 8의 정적을 두어 클라이맥스를
  살린다. 정지 중에는 미세한 흔들림 외에 아무것도 움직이지 않는다.
- **금지** — 슬라이드쇼(앞에 다 쏟고 얼어붙기)와 화면보호기(전부 따로 떠다니기)
  둘 다 금지. 브라우저 크롬·스크롤바·커서·보라파랑 AI 그라디언트·보케 금지.
  기기가 진동한다는 표현 금지(모터 없음). 실측 안 한 수치 금지.
- **소재** — 실물 사진이 하나도 없다. 전부 HTML/SVG로 그린다. 사진인 척하는
  이미지를 만들지 않는다. 기기는 **도해(diagram)** 로 그린다.

---

## Frame 1 — 벽
- src: compositions/frames/01-wall.html
- status: animated

- scene: 아이콘이 빽빽한 홈 화면 앞에서 손가락이 멈춘다
- reading: "전화 한 통 거는 데 화면 세 번, 아이콘 스무 개." → "익숙하지 않은 사람에게는 그게 벽입니다."
- transition_in: cut
- type: pain_point
- persuasion: Pain agitation
- beat: 막막함
- duration: 22s
- blueprint: overwhelm-surround (Adapt)
- focal: 폰 화면 격자 (그래픽)
- asset_candidates:
- assets_note: 실물 소재 없음 · 전부 코드로 그림 — 전부 그래픽 제작. 폰 목업 + 5×4 아이콘 격자.
- roles: 아이콘 격자 = cutout · 캔버스 = background

Adapt: 원 blueprint의 "사방에서 몰려드는" 압박감은 살리되, 몰려드는 주체를
알림이 아니라 **아이콘 자체**로 바꾼다. 아이콘은 움직이지 않고, 대신 읽을 수
없게 되어 간다.

Scene 1 (0.0–5.0s): 캔버스 위에 폰 목업 하나만. 세로 중앙, 화면의 ~42%.
5×4 아이콘 격자가 waterfall-entry로 위에서부터 차례로 내려앉는다. 소리도 글자도
없이 격자만 채워진다. Centered, 2 depth layers.
Scene 2 (5.0–10.0s): 손가락(단순 도형, 사진 아님)이 아래에서 올라와 격자 위
2/3 지점에서 멈춘다. 멈춘 채 1.5초. 그리고 그대로 내려간다. 아무것도 눌리지 않는다.
Scene 3 (10.0–15.0s): 아이콘 이름들이 아이콘별 시차를 두고 흐려진다
(depth-of-field-blur). 격자는 그대로인데 읽을 수가 없어진다. 동시에 왼쪽
상단 여백에 첫 문장이 한 줄씩 올라온다 — "전화 한 통 거는 데 / 화면 세 번, 아이콘 스무 개."
Asymmetric 60/40.
Scene 4 (15.0–22.0s): 폰 목업이 어두워지며 화면 밖으로 물러난다. 잉크색 면이
아래에서 올라와 캔버스를 덮고, 그 위에 두 번째 문장만 남는다 —
**"익숙하지 않은 사람에게는 그게 벽입니다."** 문장이 들어온 뒤 완전히 정지.
이 정적이 프레임 2의 등장을 만든다.

---

## Frame 2 — 버튼 세 개
- src: compositions/frames/02-three-buttons.html
- status: animated

- scene: 바이브키 기기가 도해로 그려지고, 버튼 3개에 이름이 붙는다
- reading: "바이브키." → "버튼은 세 개뿐입니다." → "USB로 꽂으면 끝. 인터넷도, 계정도 필요 없습니다."
- transition_in: crossfade
- type: solution_reveal
- persuasion: Relief / simplicity
- beat: 숨통
- duration: 18s
- blueprint: device-surface-showcase (Adapt)
- focal: 기기 도해
- asset_candidates:
- assets_note: 실물 소재 없음 · 전부 코드로 그림 — SVG 도해. XIAO ESP32-S3 보드 + 버튼 3개 + USB-C.
- roles: 기기 도해 = cutout · 캔버스 = background

Adapt: 제품 사진 대신 **선으로 그린 도해**를 쓴다. 사진이 없다는 사실을 숨기지
않고, 오히려 회로도의 정직함으로 바꾼다.

Scene 1 (0.0–4.0s): 잉크 면이 걷히고 캔버스가 돌아온다. 화면 정중앙에 기기
외곽선이 svg-path-draw로 **그려진다** — 한 획으로 이어지듯. 아직 이름 없음.
Centered, ~46%.
Scene 2 (4.0–7.0s): 워드마크 **바이브키**가 도해 위쪽에 h1으로 앉는다.
spring-pop-entrance, 짧고 단단하게.
Scene 3 (7.0–12.0s): 버튼 3개가 코발트·초록·호박으로 차례로 점등되고
(0.35s 간격), 각 버튼에서 지시선이 뻗어 라벨이 붙는다 — `1 전화` `2 문자` `3 길찾기`.
지시선은 그려지고, 라벨은 뒤따라 페이드. 오른쪽에 본문 한 줄: "버튼은 세 개뿐입니다."
Rule-of-thirds, 3 depth layers.
Scene 4 (12.0–18.0s): 기기 오른쪽에서 USB-C 케이블이 그려져 나와 프레임
오른쪽 끝의 폰 실루엣에 닿는다. 케이블 위로 코발트 빛이 기기→폰 방향으로 한 번
흐른다(gradient-text-sweep 응용, 방향이 중요). 하단에 마지막 줄:
"USB로 꽂으면 끝. 인터넷도, 계정도 필요 없습니다." 흐름이 끝나면 정지.

---

## Frame 3 — 누르면 열린다
- src: compositions/frames/03-press-opens.html
- status: animated

- scene: 버튼을 누르면 폰에서 앱이 열린다. 세 번 반복되는 같은 리듬.
- reading: "1번을 누르면 전화 앱이 열립니다." → "2번은 문자." → "3번은 길찾기."
- transition_in: cut
- type: demo
- persuasion: Proof by demonstration
- beat: 확신
- duration: 26s
- blueprint: cursor-ui-demo (Adapt)
- focal: 기기 + 폰 2분할
- asset_candidates:
- assets_note: 실물 소재 없음 · 전부 코드로 그림 — 기기 도해(왼쪽) + 폰 UI 재현(오른쪽). 앱 화면은 activity_main.xml 기준.
- roles: 기기 도해 = supporting · 폰 UI = cutout · 캔버스 = background

Adapt: 커서 대신 **버튼 눌림**이 입력이다. UI 데모의 "입력 → 반응" 구조는 그대로
쓰되, 입력이 화면 밖(하드웨어)에서 온다는 점을 2분할로 보여 준다.

Scene 1 (0.0–3.0s): split-screen 확정 — 왼쪽 40% 기기 도해, 오른쪽 60% 폰.
폰 화면은 바이브키 앱 홈(버튼 3칸). 아직 아무 일도 없음. 상단에 얇은
크롬 라벨 `실제 동작`.
Scene 2 (3.0–10.0s): 1번 버튼이 눌린다 — press-release-spring으로 눌리고,
눌린 순간 버튼에서 코발트 링이 퍼진다(cursor-click-ripple 응용). 링이 케이블을
타고 오른쪽으로 건너가 폰에 닿고, 폰이 짧게 흔들린다(폰 진동).
그 직후 폰 화면이 전화 앱으로 scale-swap-transition. 번호가 채워져 있다.
오른쪽 하단에 배지 `짧게 누르기`.
Scene 3 (10.0–17.0s): 같은 동작이 2번 버튼(초록)에서 반복 — 링, 진동, 문자 앱.
리듬을 정확히 같게 유지한다. 반복이 곧 "항상 이렇게 된다"는 증거다.
Scene 4 (17.0–23.0s): 3번 버튼(호박) → 지도. 세 번째는 앞의 둘보다 0.2초 빠르게
넘겨 리듬에 가속을 준다.
Scene 5 (23.0–26.0s): 폰 화면이 앱 홈으로 돌아오고, 세 버튼 칸에 방금 연 앱
아이콘 3개가 나란히 남는다. 정지.

---

## Frame 4 — 짧게, 그리고 길게
- src: compositions/frames/04-short-long.html
- status: animated

- scene: 같은 버튼도 길게 누르면 다른 일을 한다
- reading: "같은 버튼도 길게 누르면 다른 일을 합니다." → "버튼 3개로 6가지."
- transition_in: wipe
- type: feature
- persuasion: Capacity beyond appearance
- beat: 확장
- duration: 16s
- blueprint: comparison-split (Reproduce)
- focal: 좌우 대비 패널
- asset_candidates:
- assets_note: 실물 소재 없음 · 전부 코드로 그림 — 좌우 대칭 패널 2개 + 눌림 시간 게이지.
- roles: 대비 패널 = cutout · 캔버스 = background

Scene 1 (0.0–4.0s): 화면이 세로로 정확히 반으로 갈린다. 양쪽 모두 같은 3번
버튼 도해. 왼쪽 위 크롬 `짧게`, 오른쪽 위 크롬 `길게`. 아직 눌리지 않음.
Split-screen, 대칭.
Scene 2 (4.0–8.0s): 양쪽 버튼이 **동시에** 눌린다. 각 버튼 아래 게이지가 차오르는데,
왼쪽은 0.2초에서 멈추고 오른쪽은 계속 차서 0.7초 선을 넘는다. 넘는 순간
오른쪽 게이지가 코발트로 점등.
Scene 3 (8.0–12.0s): 왼쪽 결과 `길찾기`, 오른쪽 결과 `AI 도우미`가 각각
패널 하단에 spring-pop-entrance로 앉는다. 결과가 다르다는 것이 한눈에 보인다.
Scene 4 (12.0–16.0s): 분할선이 사라지고 하단 중앙에 한 줄만 남는다 —
**`3개 버튼 × 2가지 누름 = 6가지 동작`**. 숫자는 코발트. 등장 후 **완전 정지**.
이 프레임은 호흡 프레임이다 — 읽게 두고 아무것도 움직이지 않는다.

---

## Frame 5 — 안을 열면
- src: compositions/frames/05-inside.html
- status: animated

- scene: 버튼 눌림이 폰에 닿기까지 기기 안에서 일어나는 일
- reading: "버튼이 눌리는 순간을 인터럽트로 잡고, 태스크 두 개가 나눠 처리합니다." → "채터링은 여기서 걸러집니다."
- transition_in: crossfade
- type: mechanism
- persuasion: Technical credibility
- beat: 깊이
- duration: 20s
- blueprint: spatial-pan-stations (Adapt)
- focal: 신호 경로 타임라인
- asset_candidates:
- assets_note: 실물 소재 없음 · 전부 코드로 그림 — 5개 정거장 파이프라인 도해.
- roles: 파이프라인 = cutout · 기기 외곽선 = background (dim ~35%)

Adapt: 공간 이동 대신 **신호 하나를 따라간다.** 카메라가 옆으로 흐르는 게 아니라
점 하나가 정거장을 지나간다.

Scene 1 (0.0–4.0s): 기기 도해가 흐려지며 배경으로 물러나고(dim ~35%), 그 위에
가로 파이프라인 축이 svg-path-draw로 왼쪽에서 오른쪽으로 그려진다.
Full-width strip, 3 depth layers.
Scene 2 (4.0–9.0s): 정거장 5개가 waterfall-entry로 왼쪽부터 하나씩 —
`버튼` → `ISR` → `큐` → `입력 태스크` → `프로토콜 태스크` → `USB`.
각 정거장 아래 작은 mono 라벨(`IRAM_ATTR`, `xQueueSendFromISR`, `10ms`, `5ms`).
Scene 3 (9.0–14.0s): 코발트 점 하나가 `버튼`에서 출발해 정거장을 지나간다.
지나갈 때마다 그 정거장이 잠깐 점등. `입력 태스크`에서 점이 잠시 머물고,
그 아래 `채터링 25ms 제거` 라벨이 붙으며 지저분한 신호가 깨끗한 한 번으로
정리되는 미니 그래프가 나타난다.
Scene 4 (14.0–20.0s): 점이 `USB`에 도달. 오른쪽 끝에 결과 한 줄 —
"버튼이 눌리는 순간을 인터럽트로 잡습니다. 폴링이 아닙니다."
파이프라인 전체가 한 번 얕게 밝아졌다가 정지.

---

## Frame 6 — 프레임에 담는다
- src: compositions/frames/06-frame-assembly.html
- status: animated

- scene: 눌림 정보가 VKP 프레임으로 조립된다
- reading: "전선으로 그냥 보내지 않습니다." → "직접 정한 형식에 담고, CRC로 서명합니다."
- transition_in: cut
- type: mechanism
- persuasion: Engineering rigor
- beat: 정교함
- duration: 18s
- blueprint: grid-card-assemble (Adapt)
- focal: 바이트 블록 열
- asset_candidates:
- assets_note: 실물 소재 없음 · 전부 코드로 그림 — 7개 바이트 블록 + 헥사값.
- roles: 바이트 블록 = cutout · 캔버스 = background

Adapt: 카드 격자 대신 **한 줄로 늘어선 바이트 블록**. 조립되는 느낌은 그대로.

Scene 1 (0.0–3.0s): 화면 중앙에 빈 슬롯 7칸이 얇은 선으로 그려진다.
위에 크롬 라벨 `VKP v1`. Centered, ~70% 폭.
Scene 2 (3.0–9.0s): 블록이 왼쪽부터 하나씩 날아와 슬롯에 꽂힌다
(discrete-text-sequence, 0.5s 간격). 각 블록에 이름과 값이 함께 —
`AA`(시작) · `SEQ`(순번) · `TYPE`(종류) · `LEN`(길이) · `데이터` · `CRC16` · `55`(끝).
꽂힐 때마다 짧은 임팩트.
Scene 3 (9.0–14.0s): `CRC16` 블록이 꽂히는 순간, 앞의 `SEQ`~`데이터` 구간 위로
코발트 밑줄이 훑고 지나가며 그 결과가 CRC 블록 안의 값으로 떨어진다.
"이 구간을 계산해 서명한다"가 동작으로 보인다.
Scene 4 (14.0–18.0s): 완성된 7블록이 한 덩어리로 살짝 모이고, 아래에
mono 한 줄 `AA │ SEQ │ TYPE │ LEN │ 데이터 │ CRC16 │ 55`. 정지.

---

## Frame 7 — 틀어지면 버린다
- src: compositions/frames/07-crc-reject.html
- status: animated

- scene: 전송 중 비트가 손상되면 폰이 받아들이지 않고 다시 받는다
- reading: "한 비트라도 틀어지면 버립니다." → "잘못 눌린 척하는 신호가 앱에 닿지 않도록."
- transition_in: cut
- type: mechanism
- persuasion: Safety / trust
- beat: 긴장 → 해소
- duration: 18s
- blueprint: fixed-anchor-cycle (Adapt)
- focal: 프레임 하나의 왕복
- asset_candidates:
- assets_note: 실물 소재 없음 · 전부 코드로 그림 — 프레임 블록 + 기기/폰 양끝 + 판정 배지.
- roles: 프레임 블록 = cutout · 기기·폰 = supporting

Adapt: 순환 구조를 **한 번의 실패와 한 번의 성공**으로 쓴다. 같은 자리에서 두 번
일어나므로 차이가 선명하다.

Scene 1 (0.0–4.0s): 프레임 6의 블록 덩어리가 그대로 이어받아 왼쪽 기기에서
출발해 오른쪽 폰을 향해 이동한다. 이동 중.
Scene 2 (4.0–8.0s): 중간 지점에서 블록 몇 개의 비트가 **적색으로 뒤집힌다**
(chromatic-glitch, 짧고 거칠게). 폰에 닿는 순간 판정 배지 `✕ CRC 불일치`가
적색으로 뜨고, 프레임이 particle-burst로 흩어져 사라진다. 앱은 아무 반응 없음 —
폰 화면은 그대로다. 이게 요점이다.
Scene 3 (8.0–13.0s): 기기 쪽에 `ACK 없음 → 재전송` 라벨이 뜨고, 같은 프레임이
다시 출발한다. 이번에는 손상 없이 도착. 배지 `✓ CRC 일치`가 코발트로.
폰 화면이 그제서야 반응한다.
Scene 4 (13.0–18.0s): 하단에 근거 한 줄이 작은 mono로 —
`firmware/test/test_vkp.cpp · 손상 주입 10만 건 검증`. 정지.

---

## Frame 8 — 멈추면 스스로 되살아난다
- src: compositions/frames/08-watchdog.html
- status: animated
- status: animated

- scene: 워치독이 기기를 재시작하고 원인을 폰에 보고한다
- reading: "기기가 멈추면 스스로 되살아나고, 왜 멈췄는지 폰에 알립니다."
- transition_in: crossfade
- type: mechanism
- persuasion: Reliability
- beat: 안도
- duration: 12s
- blueprint: fixed-anchor-cycle (Reproduce)
- focal: 워치독 링
- asset_candidates:
- assets_note: 실물 소재 없음 · 전부 코드로 그림 — 카운트다운 링 + 상태 카드.
- roles: 워치독 링 = cutout · 폰 카드 = supporting

Scene 1 (0.0–3.5s): 화면 중앙에 얇은 링. 안쪽에 mono `태스크 워치독`.
링이 시계방향으로 채워지기 시작한다. 정상 상태에서는 계속 초기화되며
채워지다 말다 한다(sine-wave-loop 느낌의 미세한 리셋).
Scene 2 (3.5–7.0s): 리셋이 멈춘다. 링이 끝까지 차오르고 `5s`에서 완주.
완주 순간 화면이 한 번 어두워졌다가 즉시 밝아진다 — 재시작.
Scene 3 (7.0–12.0s): 오른쪽에 폰 상태 카드가 spring-pop-entrance로 올라오고,
카드에 한 줄 — `재시작 원인: 워치독`. 카드가 앉은 뒤 **완전 정지**.
프레임 9 직전의 호흡. 여기서 정적을 충분히 준다.

---

## Frame 9 — 숫자
- src: compositions/frames/09-numbers.html
- status: animated

- scene: 확인된 수치 네 개
- reading: "자동 테스트 89개. 플래시 8%. RAM 6%. 부품 5종 14,690원."
- transition_in: cut
- type: proof
- persuasion: Evidence
- beat: 확정
- duration: 15s
- blueprint: dataviz-countup (Reproduce)
- focal: 수치 4개 격자
- asset_candidates:
- assets_note: 실물 소재 없음 · 전부 코드로 그림 — 2×2 수치 카드.
- roles: 수치 카드 = cutout · 캔버스 = background

Scene 1 (0.0–3.0s): 2×2 격자 슬롯이 그려진다. 상단에 크롬 `확인된 수치`.
Centered, ~64%.
Scene 2 (3.0–11.0s): 카드가 0.6초 간격으로 하나씩 들어오고, 각 숫자가
counting-dynamic-scale로 0에서 올라간다. 숫자는 코발트, 단위는 잉크.
  · `89` 자동 테스트 (펌웨어 41 + 앱 48)
  · `8%` 플래시 사용 — 277,057 B
  · `6%` RAM 사용 — 22,608 B
  · `14,690원` 부품 5종
Scene 3 (11.0–15.0s): 네 카드가 다 차오른 뒤 아래에 작은 본문 한 줄 —
"모두 빌드 산출물과 테스트 실행에서 나온 값입니다." 정지.

---

## Frame 10 — 버튼 세 번
- src: compositions/frames/10-three-presses.html
- status: animated

- scene: 1번 구간의 손이 돌아와, 이번에는 누른다
- reading: "어르신도, 버튼 세 번이면 끝."
- transition_in: crossfade
- type: close
- persuasion: Callback
- beat: 수렴
- duration: 10s
- blueprint: titlecard-reveal (Adapt)
- focal: 손 + 버튼 3개
- asset_candidates:
- assets_note: 실물 소재 없음 · 전부 코드로 그림 — 프레임 1의 손 도형 재사용 + 기기 버튼 3개.
- roles: 손 = cutout · 기기 = supporting

Adapt: 타이틀 카드를 **동작 뒤에** 놓는다. 먼저 누르고, 그 다음 문장이 온다.

Scene 1 (0.0–4.0s): 프레임 1과 정확히 같은 위치에서 같은 손 도형이 올라온다.
이번에는 멈추지 않는다. 1번·2번·3번을 리듬 있게 누른다(0.7초 간격,
press-release-spring). 누를 때마다 해당 색이 점등.
Scene 2 (4.0–7.5s): 손이 내려가고, 점등된 세 색이 남는다. 그 위로 문장이
한 번에 앉는다 — **"어르신도, 버튼 세 번이면 끝."** h1, 잉크색.
Scene 3 (7.5–10.0s): 캔버스가 잉크 면으로 덮이고, 중앙에 워드마크
**바이브키**와 아래 작은 크롬 두 줄 — `팀 뚝딱뚝딱` / `2026 임베디드SW 경진대회 자유공모`.
정지 후 종료.
