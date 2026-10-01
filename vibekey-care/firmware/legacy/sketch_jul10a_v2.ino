                                
                                
const int BUTTON_1_PIN = D0; // 첫 번째 버튼 (Button1)
const int BUTTON_2_PIN = D1; // 두 번째 버튼 (Button2)
const int BUTTON_3_PIN = D2; // 세 번째 버튼 (Button3)

void setup() {
  // 안드로이드 시리얼 통신을 위한 세팅
  Serial.begin(115200);
  
  // 3개의 버튼 모두 풀업 저항 모드로 설정 (GND와 연결해 사용할 것)
  pinMode(BUTTON_1_PIN, INPUT_PULLUP);
  pinMode(BUTTON_2_PIN, INPUT_PULLUP);
  pinMode(BUTTON_3_PIN, INPUT_PULLUP);
}

void loop() {
  // 1번 버튼 (D0) 클릭 감지
  if (digitalRead(BUTTON_1_PIN) == LOW) {
    Serial.println("Button1"); // 안드로이드 앱이 기다리는 마법의 단어!
    delay(200); // 연속 입력 방지 및 디바운스
    while(digitalRead(BUTTON_1_PIN) == LOW) { delay(10); } // 손 뗄 때까지 대기
  }

  // 2번 버튼 (D1) 클릭 감지
  if (digitalRead(BUTTON_2_PIN) == LOW) {
    Serial.println("Button2");
    delay(200);
    while(digitalRead(BUTTON_2_PIN) == LOW) { delay(10); }
  }

  // 3번 버튼 (D2) 클릭 감지
  if (digitalRead(BUTTON_3_PIN) == LOW) {
    Serial.println("Button3");
    delay(200);
    while(digitalRead(BUTTON_3_PIN) == LOW) { delay(10); }
  }

  delay(20); // CPU 과부하 방지를 위한 미세한 딜레이
}
                            