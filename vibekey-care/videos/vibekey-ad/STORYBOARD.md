---
format: 1920x1080
duration: 55s
message: "복잡한 화면을 지우고, 단추 세 개만 남겼습니다"
arc: 벽 → 지움 → 단추 세 개 → 고르기 → AI 배치 → 누르면 열림 → 여섯 가지 → 지켜 줌 → 브랜드
audience: "어르신을 모시는 자녀 세대 + 해커톤·경진대회 심사위원"
music: none
mode: autonomous
---

## Video direction

이 영상은 **무음**이다. 내레이션도 배경음악도 없다. 그래서 아래 두 가지가 보통의 광고와 다르다.

- **읽는 속도가 곧 편집 속도다.** 보통 프레임은 말소리에 맞춰 요소를 푸는데, 여기서는
  **화면에 뜨는 한글 문장**이 그 자리를 대신한다. 각 프레임의 `voiceover` 칸은 소리가 아니라
  **화면에 뜨는 글**이다. 한 줄이 눈에 들어오는 데 1.6~2.2초를 준다. 그보다 빠르면 못 읽는다.
- **컷은 0.5초의 배수로 끊는다.** 나중에 음악을 얹어도 박이 맞도록 미리 격자에 올려 둔다.

**팔레트** — `frame.md` 가 색의 진실이다. 역할은 이렇게 고정한다.

| 쓰임 | 토큰 | 값 |
|---|---|---|
| 바탕 | `cream` | `#F7F9FC` |
| 글자·테두리 | `ink` | `#101418` |
| 1번 단추 | `yellow` | `#0B57D0` |
| 2번 단추 | `green` | `#12703A` |
| 3번 단추 | `orange` | `#B26A00` |
| AI | `pink` | `#8A2BE2` |

**단추 색은 이 영상의 문법이다.** 어떤 프레임에서든 1번을 말하면 코발트, 2번이면 초록,
3번이면 호박이다. 이 규칙이 깨지면 "기기의 단추 = 앱의 그 자리"라는 개념 자체가 무너진다.

**모션 문법** — 기본 이즈는 긴 꼬리(`power3`). 튀는 것보다 매끄러운 쪽. 요소는
**글이 그 말을 할 때** 나온다. 절대 t=0에 전부 쏟지 않는다. 프레임 뒤쪽 절반에도 반드시
새로 나오는 것이 있어야 한다.

**멈추는 프레임** — 3번(단추 세 개)과 9번(브랜드)은 다 나온 뒤 **가만히 있는다**.
전부가 계속 움직이면 아무것도 강조되지 않는다. 억지 카메라 드리프트·의미 없는 숨쉬기 금지.

**절대 넣지 않는 것**

- 브라우저 크롬·스크롤바·내비게이션 바·실제 마우스 커서
- 떠다니는 보라-파랑 "AI 그라데이션", 보케, 장식용 도형
- **슬라이드쇼**(앞에서 다 쏟고 얼어붙기) 와 **화면보호기**(전부 따로 둥둥) 두 실패 모두
- 기기가 진동한다는 표현 (기기에 모터가 없다 — 진동은 폰이 한다)
- 실측하지 않은 수치 (버튼→앱 실행 지연 ms, 프레임 오류율 %)

**아래 17%는 비워 둔다.** 캡션은 안 쓰지만 아래 여백은 지킨다.

---

## Frame 1 — 벽

- scene: 앱 아이콘 수십 개가 화면을 메우고, 밀려나며 가운데가 열린다
- duration: 6.5s
- poster: 5.0s
- transition_in: cut
- status: outline
- src: compositions/frames/01-wall.html
- type: hook
- persuasion: 문제 제시
- beat: 막막함
- voiceover: "어머니는 매일 / 이 앞에서 멈추셨습니다"
- asset_candidates: assets/icon-wall.svg
- blueprint: overwhelm-surround (Adapt — clutter-shove-to-question)
- focal: assets/icon-wall.svg
- roles: icon-wall = background (전면을 덮음)
- sfx: none

Adapt: 원형의 "아바타로 변하는 중심"은 뺀다(우리 이야기에 사람 아이콘은 거짓이다).
**시그니처 무브인 "밀어내기(shove)"는 그대로 지킨다** — 잡동사니가 가장자리로 밀리며
가운데가 열리고 그 자리에 질문이 선다.

Scene 1 (0.0–1.8s): 크림 바탕 위로 앱 아이콘 42개가 격자로 **깜빡이며 채워진다**
(index 순서대로, 한 번에 한 줄씩 — spring-pop-entrance, 스태거는 0.5초 안에 묶음).
전면을 덮는 full-width strip, 아이콘은 무채색 회색이라 하나도 눈에 걸리지 않는다.
아직 글자는 없다. 3depth: 격자(뒤) · 미세한 그림자(중) · 없음(앞).
Scene 2 (1.8–3.2s): 격자가 **아주 느리게 뒤로 빠진다**(zoom-out, 배율 1.0→0.92).
빽빽함이 눈에 들어오는 구간. 여전히 글자는 없다 — 먼저 보게 하고 나중에 말한다.
Scene 3 (3.2–4.6s): 가운데에서 바깥으로 **밀어내기**. 아이콘들이 상하좌우 가장자리로
쓸려 나가며(center-outward-expansion 역방향) 화면 한가운데가 크림 바탕으로 비워진다.
밀려난 아이콘은 가장자리에서 잘린 채 남아 "아직 저기 있다"를 유지한다.
Scene 4 (4.6–6.5s): 열린 가운데에 문장이 **두 박으로 선다** — "어머니는 매일" 이 먼저,
0.6초 뒤 "이 앞에서 멈추셨습니다" 가 아래에 (waterfall-entry, 아래에서 위로).
Centered, 문장이 화면 폭의 60%. 마지막 1초는 완전히 멈춘 채 읽게 둔다.

---

## Frame 2 — 지웠습니다

- scene: 남아 있던 아이콘까지 전부 흩어져 사라지고, 한 문장만 남는다
- duration: 5.5s
- poster: 4.5s
- transition_in: cut
- status: outline
- src: compositions/frames/02-erased.html
- type: pain_point
- persuasion: 가치 선언
- beat: 결단
- voiceover: "그래서 화면을 지웠습니다"
- asset_candidates: assets/icon-wall.svg
- blueprint: kinetic-type-beats (Reproduce)
- focal: assets/icon-wall.svg
- roles: icon-wall = supporting (가장자리에 남은 잔해)
- sfx: none

이 프레임이 **메시지가 착지하는 자리**다(두 번째 박 안에 가치가 온다는 규칙).
앞 프레임에서 밀려나 가장자리에 걸려 있던 아이콘이 여기서 완전히 사라진다.

Scene 1 (0.0–1.2s): 앞 컷에서 이어받아, 가장자리에 잘린 채 남아 있던 아이콘들이
**바깥으로 더 밀려나며 하나씩 꺼진다**(스태거 소멸, 0.5초 안에 묶음).
화면이 점점 크림 바탕만 남는다. Centered, 아직 빈 화면.
Scene 2 (1.2–2.6s): 완전히 빈 크림 바탕. **1.4초 동안 아무것도 없다.**
이 빈 구간이 다음 문장의 무게를 만든다 — 채우지 않는다.
Scene 3 (2.6–4.0s): 가운데에 "그래서" 가 작게 뜬 뒤,
**"화면을 지웠습니다"** 가 아래에서 크게 슬램으로 꽂힌다(kinetic-beat-slam).
display-hero 급, 화면 폭의 70%. ink 검정, 강조 없음 — 색은 다음 프레임에 쓴다.
Scene 4 (4.0–5.5s): 문장 아래로 코발트 밑줄 한 줄이 좌→우로 **그어진다**
(svg-path-draw, 폭 60px×4px 규격의 accent-line). 그은 뒤 완전히 멈춘다.

---

## Frame 3 — 단추 세 개

- scene: 기기가 조립되며 나타나고, 단추 3개에 색이 들어온다
- duration: 5.0s
- poster: 4.2s
- transition_in: cut
- status: outline
- src: compositions/frames/03-three-buttons.html
- type: product_intro
- persuasion: 해결 제시
- beat: 등장
- voiceover: "단추 세 개만 남겼습니다"
- asset_candidates: assets/device-board.svg
- blueprint: logo-assemble-lockup (Adapt — 로고 대신 기기가 조립된다)
- focal: assets/device-board.svg
- roles: device-board = cutout
- sfx: none

Adapt: 브랜드 마크 자리에 **제품 실물의 벡터**를 넣는다. "부분에서 전체가 만들어져
가운데 록업으로 앉는다"는 **시그니처 무브는 그대로**다. 이 그림은 앱 소스의
`ic_device_board.xml` 을 그대로 쓴 것이라, 화면 속 기기와 한 픽셀도 다르지 않다.

Scene 1 (0.0–1.4s): 빈 크림 바탕 가운데에 기기 **몸통(어두운 라운드 사각형)만** 먼저
아래에서 올라와 앉는다(spring-pop-entrance, 오버슈트 약하게). 화면 폭의 55%, Centered.
단추 자리는 아직 비어 있다.
Scene 2 (1.4–2.3s): 1번 자리에 **코발트** 원이 팝. 0.25초 뒤 2번에 **초록**, 다시 0.25초 뒤
3번에 **호박**. 세 번 나눠 찍어야 "세 개"가 수로 읽힌다 — 한꺼번에 뜨면 그냥 무늬다.
Scene 3 (2.3–3.6s): 각 단추 아래로 색이 같은 짧은 세로선이 **아래로 자란다**
(1번 코발트 · 2번 초록 · 3번 호박). 이 선이 뒤 프레임들에서 "이 단추 = 이 앱"을 잇는 장치가 된다.
Scene 4 (3.6–5.0s): 기기 위쪽에 "단추 세 개만 남겼습니다" 가 한 박으로 들어온다.
**멈춤 프레임** — 문장이 들어온 뒤 카메라도 요소도 완전히 정지. 1.4초를 그대로 둔다.

---

## Frame 4 — 무엇을 자주 하시나요

- scene: 실제 앱 화면. 어르신이 "하고 싶은 일"을 고른다
- duration: 7.5s
- poster: 6.0s
- transition_in: cut
- status: outline
- src: compositions/frames/04-what-do-you-do.html
- type: feature_showcase
- persuasion: 사용 방식
- beat: 첫 대면
- voiceover: "앱 이름은 묻지 않습니다 / 무엇을 자주 하시는지만"
- asset_candidates: assets/clip-pick.mp4
- blueprint: device-surface-showcase (Adapt — 정적 홀드 + 요소 스왑)
- focal: assets/clip-pick.mp4
- roles: clip-pick = cutout (폰 기기틀 안의 히어로)
- sfx: none

Adapt: 3D 손·회전 없이 **정적 홀드** 변형을 쓴다. 화면 안에서 체크가 하나씩 붙는 것이
곧 카메라 워크다. 기기틀 안에 진짜 화면을 넣는 **시그니처(히어로로 붙든 표면)는 유지**.

Scene 1 (0.0–1.6s): 폰 기기틀이 오른쪽에서 **미끄러져 들어와** 화면 오른쪽 55% 자리에 선다
(nudge-curve, 느리게-빠르게-느리게). asymmetric 60/40, 폰이 40 쪽. 영상은 정지 상태로 대기.
Scene 2 (1.6–3.0s): 왼쪽 60에 "앱 이름은 묻지 않습니다" 가 들어온다.
아래에 작게 "무엇을 자주 하시는지만". 폰 화면 재생 시작 — 첫 번째 체크(전화 걸기)가 붙는다.
Scene 3 (3.0–5.2s): 폰 화면에서 **두 번째·세 번째 체크**가 차례로 붙는다(길 찾기 · 사진 보기).
그때마다 왼쪽 여백에 그 기능 이름이 색 점과 함께 하나씩 쌓인다 —
전화 걸기(코발트) · 길 찾기(초록) · 사진 보기(호박). 이 색이 곧 몇 번 단추인지를 미리 말해 준다.
Scene 4 (5.2–7.5s): 폰 화면 아래의 "3가지를 고르셨어요" 에 맞춰 왼쪽 목록 셋이
**동시에 한 번 밝아졌다 가라앉는다**. 그 뒤 정지. 마지막 1.3초는 읽는 시간.

---

## Frame 5 — AI가 정합니다

- scene: 실제 앱 화면. 고른 일이 1·2·3번 단추로 배치된다
- duration: 7.0s
- poster: 5.6s
- transition_in: cut
- status: outline
- src: compositions/frames/05-ai-assigns.html
- type: feature_showcase
- persuasion: 핵심 기능
- beat: 해결
- voiceover: "AI가 단추를 정합니다"
- asset_candidates: assets/clip-result.mp4, assets/device-board.svg
- blueprint: device-surface-showcase (Adapt — 표면에서 정보 카드가 튀어나옴)
- focal: assets/clip-result.mp4
- roles: clip-result = cutout · device-board = supporting (위쪽에 작게, 색 잇기용)
- sfx: none

Adapt: 히어로 표면은 유지하되, 화면 안의 세 줄을 **밖으로 꺼내 크게 세운다**.
어르신이 실제로 읽는 크기는 폰 안에서 작다 — 광고에서는 그 세 줄이 주인공이라 밖으로 꺼낸다.
**주의**: 이 클립 위쪽의 "인터넷 없이 정했어요" 안내 줄은 화면에 넣지 않는다.
클립을 기기 그림 위쪽부터 잘라 쓴다.

Scene 1 (0.0–1.4s): 폰이 앞 프레임 자리에서 **가운데로 옮겨 앉으며** 살짝 작아진다.
화면에는 배치 결과가 이미 떠 있다. 위쪽에 "AI가 단추를 정합니다" 가 보라(pink)로 한 박.
Centered, 폰이 화면 높이의 70%.
Scene 2 (1.4–3.0s): 폰 화면의 **1번 줄이 밖으로 튀어나와** 왼쪽에 큰 카드로 선다 —
코발트 세로 막대 + "1번 단추" + "Phone". 폰 안의 그 줄에는 코발트 테두리가 남는다.
Scene 3 (3.0–4.4s): 같은 방식으로 **2번(초록, Maps)** 이 오른쪽 위로 튀어나온다.
Scene 4 (4.4–5.6s): **3번(호박, Photos)** 이 오른쪽 아래로. 세 카드가 폰을 둘러싼 배치가 된다.
triptych 느낌, 3 depth layers(폰=앞, 카드=중, 크림 바탕=뒤).
Scene 5 (5.6–7.0s): 세 카드와 폰 화면의 해당 줄을 **같은 색 실선이 잇는다**
(svg-path-draw, 세 줄이 0.15초 간격으로 그어짐). 그은 뒤 정지.

---

## Frame 6 — 누르면 열립니다

- scene: 실제 앱 화면. 단추를 누르자 전화 앱이 열린다
- duration: 7.5s
- poster: 6.2s
- transition_in: cut
- status: outline
- src: compositions/frames/06-press-opens.html
- type: feature_showcase
- persuasion: 결정적 증거
- beat: 실행
- voiceover: "누르면 / 열립니다"
- asset_candidates: assets/clip-press.mp4, assets/device-board.svg
- blueprint: device-surface-showcase (Reproduce — 히어로 표면 + 연속 푸시인)
- focal: assets/clip-press.mp4
- roles: clip-press = cutout · device-board = supporting (왼쪽, 1번 단추가 눌리는 쪽)
- sfx: none

이 프레임이 **광고 전체의 증명**이다. 앞의 모든 문장이 여기서 사실로 바뀐다.
그래서 다른 어떤 프레임보다 화면을 크게 쓰고, 카메라가 유일하게 계속 움직인다.

Scene 1 (0.0–1.5s): 왼쪽에 기기 그림, 오른쪽에 폰. 기기의 **1번 단추(코발트)가 눌린다**
— 아래로 눌렸다 튀어 오르는 짧은 반동과, 그 자리에서 한 번 퍼지는 코발트 빛
(press-release-spring 의 누름 + 퍼짐을 한 번에). 커서는 그리지 않는다. split-screen 50/50.
Scene 2 (1.5–3.2s): 파문이 오른쪽 폰으로 건너가고, 폰 화면에 "1번 버튼 / 지금은
'Phone'이 열려요" 가 뜬다. 동시에 카메라가 폰 쪽으로 **천천히 밀고 들어간다**
(multi-phase-camera 의 push 단계, 배율 1.0→1.15). 기기 그림은 왼쪽에서 흐려진다.
Scene 3 (3.2–5.4s): 폰 화면에서 전화 앱이 실제로 열린다. 카메라는 계속 밀고 들어가
폰이 화면 높이의 90%를 차지한다. 이 구간에는 글자를 얹지 않는다 — 앱이 열리는 것만 본다.
Scene 4 (5.4–7.5s): 카메라가 멈추고, 폰 위에 "누르면" · "열립니다" 가 두 박으로 크게 얹힌다
(kinetic-beat-slam, 0.5초 간격). ink 검정에 "열립니다" 만 코발트. 마지막 1초 정지.

---

## Frame 7 — 짧게, 그리고 길게

- scene: 단추 3개가 짧게·길게로 갈라져 여섯 칸이 된다
- duration: 5.0s
- poster: 4.2s
- transition_in: cut
- status: outline
- src: compositions/frames/07-short-and-long.html
- type: benefit_highlight
- persuasion: 범위
- beat: 확장
- voiceover: "단추는 셋, 할 수 있는 건 여섯"
- asset_candidates: assets/device-board.svg, assets/icon-row.svg
- blueprint: grid-card-assemble (Adapt — 3×2 격자가 두 줄로 나뉘어 조립)
- focal: assets/icon-row.svg
- roles: icon-row = cutout · device-board = supporting (위쪽 작게)
- sfx: none

Adapt: 한꺼번에 쏟아지는 격자 대신 **위 줄(짧게) → 아래 줄(길게)** 두 박으로 나눈다.
"셋이 여섯이 된다"는 산수가 눈에 보여야 하기 때문이다. 스태거 조립이라는 시그니처는 유지.

Scene 1 (0.0–1.2s): 위쪽에 기기 그림이 작게 앉고, 그 아래로 단추 3색 세로선이 내려온다.
선 끝에 빈 칸 세 개가 열린다. rule-of-thirds, 격자는 화면 폭의 70%.
Scene 2 (1.2–2.4s): 위 줄 세 칸이 왼쪽부터 차례로 채워진다 —
전화(코발트) · 카카오톡(초록) · 길찾기(호박). 왼쪽에 "짧게 누르면" 라벨.
Scene 3 (2.4–3.6s): 세 색 선이 **한 칸씩 더 아래로 자라며** 아래 줄 세 칸이 열리고,
셋 다 보라색 AI 표시로 채워진다. 왼쪽에 "길게 누르면" 라벨.
Scene 4 (3.6–5.0s): 오른쪽에 "3 × 2 = 6" 이 아니라 **"단추는 셋, 할 수 있는 건 여섯"** 이
한 박으로 들어온다. "여섯" 만 코발트. 그 뒤 정지.

---

## Frame 8 — 주머니에서는 열리지 않습니다

- scene: 옷감에 눌린 신호가 기기 안에서 걸러진다
- duration: 5.0s
- poster: 4.2s
- transition_in: cut
- status: outline
- src: compositions/frames/08-pocket-guard.html
- type: benefit_highlight
- persuasion: 신뢰
- beat: 안심
- voiceover: "주머니에서 눌린 것은 / 기기가 먼저 걸러 냅니다"
- asset_candidates: assets/device-board.svg
- blueprint: kinetic-type-beats (Adapt — 글 사이에 한 번의 도형 사건)
- focal: assets/device-board.svg
- roles: device-board = cutout
- sfx: none

Adapt: 순수 타이포 릴레이에 **"걸러진다"는 사건 한 번**을 끼워 넣는다.
문장만으로는 "막는다"가 눈에 안 보이기 때문이다. 박자로 문장이 오는 시그니처는 유지.

이 프레임은 실측하지 않은 수치를 쓰지 않는다. 보여 주는 것은 **동작의 모양**뿐이다.

Scene 1 (0.0–1.3s): 기기 그림이 가운데. 단추 **세 개가 한꺼번에** 눌린다 —
사람 손가락으로는 안 되는 모양. 세 색이 동시에 번쩍인다.
Scene 2 (1.3–2.6s): 눌림에서 나온 신호 덩어리가 기기 밖으로 나가려다,
기기 테두리에서 **되튕겨 사라진다**(밖으로 나가는 궤적이 중간에 꺾여 소멸).
그 자리에 ✕ 표시가 한 번 찍혔다 사라진다.
Scene 3 (2.6–5.0s): 아래에 두 박으로 문장 — "주머니에서 눌린 것은" 뒤에
"기기가 먼저 걸러 냅니다". "기기가" 만 코발트. 마지막 1.4초 정지.

---

## Frame 9 — 바이브키 케어

- scene: 기기와 폰이 나란히 서고, 이름이 자리를 잡는다
- duration: 6.0s
- poster: 5.0s
- transition_in: cut
- status: outline
- src: compositions/frames/09-brand.html
- type: branding
- persuasion: 각인
- beat: 마무리
- voiceover: "바이브키 케어 / 버튼 하나로 앱이 열립니다"
- asset_candidates: assets/device-board.svg, assets/clip-home.mp4
- blueprint: logo-assemble-lockup (Adapt — 이름이 세 색에서 모여 든다)
- focal: assets/device-board.svg
- roles: device-board = cutout · clip-home = supporting (오른쪽, 실제 홈 화면)
- sfx: none

Adapt: 마크가 부품에서 조립되는 시그니처를 지키되, **부품이 세 단추 색**이다.
1번 프레임의 "벽"과 같은 구도(가운데가 열려 있고 가장자리가 채워짐)로 돌아와 수미상관을 맺는다.

Scene 1 (0.0–1.5s): 기기 그림이 왼쪽, 실제 홈 화면이 담긴 폰이 오른쪽에 나란히 선다.
둘 다 아래에서 올라온다. split-screen 50/50, 3 depth layers.
Scene 2 (1.5–2.8s): 두 화면의 **같은 자리(1·2·3번)** 를 색 실선이 가로질러 잇는다 —
기기의 코발트 단추와 폰 화면의 Phone 칸이 한 선으로 이어진다. 초록·호박도 차례로.
이 영상 전체가 말해 온 것이 여기서 한 그림이 된다.
Scene 3 (2.8–4.2s): 두 화면이 좌우로 물러나며 가운데가 열리고, 세 색 조각이
가운데로 모여 들어 **"바이브키 케어"** 글자가 된다(depth-scatter-assemble 의 역방향 조립).
Scene 4 (4.2–6.0s): 이름 아래에 "버튼 하나로 앱이 열립니다" 가 작게 한 박.
**멈춤 프레임** — 마지막 1.8초는 완전히 정지한 채 끝난다. 페이드아웃 없이 컷.
