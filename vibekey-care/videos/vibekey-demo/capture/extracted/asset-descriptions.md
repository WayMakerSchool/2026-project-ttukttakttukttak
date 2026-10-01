# 소재 목록

실물 사진·앱 화면 녹화·촬영본이 **하나도 없다.** 웹사이트도 없어 크롤링하지 않았다.
따라서 모든 화면은 저장소의 코드·회로 정보·문서를 근거로 **그래픽으로 제작**한다.

## 그래픽으로 만들 것

| 이름 | 내용 | 근거 |
|---|---|---|
| `device-diagram` | XIAO ESP32-S3 + 버튼 3개 + USB 배선도 | `firmware/README.md` 회로도, `vibekey_firmware.ino` 핀맵 |
| `phone-home-crowded` | 아이콘이 빽빽한 폰 홈 화면 (문제 제기용) | 창작 |
| `phone-app-ui` | 바이브키 앱 화면 재현 (버튼 3칸 + 앱 아이콘) | `activity_main.xml`, `MainActivity.refreshHardwareMap()` |
| `pipeline-timeline` | 버튼→ISR→큐→입력태스크→프로토콜태스크→USB | `vibekey_firmware.ino` 태스크 구성 |
| `vkp-frame` | `AA │ SEQ │ TYPE │ LEN │ 데이터 │ CRC16 │ 55` 바이트 블록 | `vkp_frame.h` |
| `crc-reject` | 비트 손상 → CRC 불일치 → 폐기 → 재전송 | `test/test_vkp.cpp` |
| `watchdog` | 멈춤 → 5초 → 자동 재시작 → 원인 보고 | `vibekey_firmware.ino` TWDT |
| `stats-counters` | 확인된 수치 4개 카운트업 | 빌드 산출물 |

## 쓰지 않는 것

- `~/Downloads/download/` 의 버튼 달린 폰 케이스 이미지 — 다른 제품(ARTIST'S PALETTE CASE)의
  AI 생성 컨셉 이미지다. 실물인 척 쓰면 안 된다.
