# 📷 책 촬영용 카메라 스케치 (XIAO ESP32-S3 Sense)

책 표지와 페이지를 찍는 카메라 보드에 올리는 스케치예요.
Arduino-ESP32의 `CameraWebServer` 예제에서 카메라 모델을 `CAMERA_MODEL_XIAO_ESP32S3`로 고른 것이에요.

## 올리는 방법

1. Arduino IDE에서 **파일 → 예제 → ESP32 → Camera → CameraWebServer**를 열어요.
   이 스케치는 예제에 함께 들어 있는 `camera_pins.h`, `app_httpd.cpp`, `camera_index.h`가 있어야 빌드돼요.
2. 예제의 `.ino` 내용을 이 폴더의 `CameraWebServer.ino`로 바꿔요.
3. `ssid`와 `password`에 내 와이파이 이름과 비밀번호를 넣어요.
4. 보드는 **XIAO_ESP32S3**, **PSRAM = OPI PSRAM**으로 맞추고 업로드해요.
5. 시리얼 모니터(115200)에 나오는 주소로 접속하면 카메라 화면이 보여요.

> ⚠️ 와이파이 비밀번호를 넣은 채로 커밋하지 마세요.
