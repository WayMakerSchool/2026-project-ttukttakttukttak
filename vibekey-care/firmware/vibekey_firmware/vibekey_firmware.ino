/*
 * ============================================================================
 *  바이브키 케어(VibeKey Care) 기기 펌웨어  ·  Seeed Studio XIAO ESP32-S3
 * ============================================================================
 *
 *  이 펌웨어가 하는 일 (= 폰이 못 하는 일)
 *  ---------------------------------------------------------------------------
 *  개발 초기의 스케치(firmware/legacy/sketch_jul10a_v2.ino)는 40줄이었습니다. loop()에서
 *  핀을 읽어 "Button1"을 println 하고, 손을 뗄 때까지 while 안에 멈춰 있었습니다.
 *  신호 해석·떨림 방지·실행 판단은 전부 안드로이드에 있었고 기기는 전선이었습니다.
 *  지금은 신뢰에 관한 판단을 전부 기기로 내렸습니다.
 *
 *  버튼 핀(D0·D1·D2)은 그때와 같습니다 — 기기를 다시 만들 필요가 없습니다.
 *
 *    1. 입력 판정   ISR → 큐 → 상태머신(디바운스·짧게/길게·떨림 가드)  [press_fsm.h]
 *    2. 전송 신뢰   프레임 + CRC16 + SEQ + ACK + 재전송 3회            [vkp_frame.h]
 *    3. 자기 감시   태스크 워치독 · 재시작 원인 기록 · 힙/큐 감시
 *
 *  기기에는 출력 장치가 없습니다(버튼 3개 + USB가 전부). 사용자에게 알리는 일은
 *  폰이 맡고(음성·진동), 기기는 "제대로 전달됐는가"를 스스로 판단해 재전송하고
 *  실패 횟수를 세어 둡니다. 그 값은 STATS 프레임으로 앱의 자가진단 화면에 나옵니다.
 *
 *  태스크 구성 (모두 core 1, Wi-Fi 없이 동작)
 *  ---------------------------------------------------------------------------
 *   이름        우선순위  주기      스택   하는 일
 *   inputTask      4     10ms     3072B  ISR 큐를 비우고 누름 종류를 확정
 *   protoTask      3      5ms     4096B  프레임 송신·ACK 대기·재전송·수신 해석
 *
 *  아두이노 IDE 설정
 *  ---------------------------------------------------------------------------
 *   보드            : XIAO_ESP32S3  (Seeed Studio XIAO ESP32S3)
 *   USB CDC On Boot : Enabled   ← 이걸 켜야 Serial이 네이티브 USB(CDC)로 나갑니다
 *   USB Mode        : USB-OTG (TinyUSB)  또는 Hardware CDC and JTAG
 *   Upload Mode     : USB-OTG CDC
 *   추가 라이브러리  : 없음 (ESP32 Arduino 코어만 있으면 빌드됩니다)
 *
 *  회로·부품·핀맵은 firmware/README.md 에 표로 정리해 두었습니다.
 * ============================================================================
 */

#include <Arduino.h>

// FreeRTOS 는 Arduino.h 가 간접적으로 끌어오지만, 큐·태스크 API를 직접 쓰므로
// 코어 버전에 상관없이 확실히 잡히도록 명시해 둡니다.
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <freertos/queue.h>

// 태스크 워치독 · 재시작 원인 · 힙 상태 — 기기가 스스로를 감시하기 위한 것들
#include <esp_task_wdt.h>
#include <esp_system.h>
#include <esp_heap_caps.h>
#include <esp_sleep.h>
#include <esp_pm.h>
#include <driver/gpio.h>
#include <Preferences.h>

#include "vkp_frame.h"
#include "press_fsm.h"
#include "press_buffer.h"
#include "slot_store.h"
#include "pocket_guard.h"
#include "power_policy.h"

using namespace vkp;

// ---------------------------------------------------------------- 버전 / 설정

static const uint8_t FW_MAJOR = 3;
static const uint8_t FW_MINOR = 0;

/** 버튼 접점 → 프레임 송출 지연을 로직 애널라이저로 재기 위한 관측 핀 */
#define VK_LATENCY_PROBE 1

/** ACK를 못 받았을 때 다시 보내는 간격과 횟수 */
static const uint16_t ACK_TIMEOUT_MS = 150;
static const uint8_t  MAX_RETRY      = 3;

/**
 * 태스크 워치독 제한 시간.
 * 두 태스크는 각각 10ms·5ms마다 도는데, 5초 동안 한 번도 신고하지 않았다면
 * 어딘가에 갇힌 것입니다. 이때는 매달려 있는 것보다 재시작이 낫습니다.
 * 어르신은 "왜 안 되지" 하고 케이블을 뽑았다 꽂는 것 말고는 할 수 있는 게 없으니,
 * 기기가 스스로 복구해야 합니다.
 */
static const uint32_t WDT_TIMEOUT_MS = 5000;

// ---------------------------------------------------------------- 저전력
//
// 이 기기는 폰에서 전원을 얻는 유선 액세서리입니다. 하루 종일 꽂아 두는 물건이므로
// 아무 일도 안 하는 동안 폰 배터리를 먹으면 안 됩니다.
//
// 다만 USB 를 쓰는 기기가 잠드는 것은 위험합니다. 그래서 조건을 좁혔습니다.
//
//   잠드는 조건 (모두 만족해야 함)
//     · 폰이 듣고 있지 않다        — DTR 이 내려가 있음 (앱이 꺼졌거나 USB 만 꽂힘)
//     · 버튼이 하나도 안 눌려 있다 — 눌린 채면 레벨 깨움이 즉시 걸려 의미가 없음
//     · 보낼 것이 남아 있지 않다   — 오프라인 버퍼가 비어 있음
//     · 마지막 활동 후 충분히 지났다
//
//   깨어나는 조건
//     · 버튼 눌림 (GPIO 로우 레벨)  — 사용자가 누르면 즉시
//     · 타이머 (0.5초)               — 깨어나 DTR 을 다시 봅니다. 앱이 다시 붙으면
//                                      최대 0.5초 안에 알아차립니다. USB 활동으로
//                                      직접 깨우는 기능은 이 칩에서 쓸 수 없습니다.
//
// 한 번에 오래 자지 않고 짧게 끊어 자는 이유는 워치독 때문입니다. 자는 동안에는
// 어느 태스크도 워치독을 먹이지 못하므로, 슬라이스가 워치독 시간을 넘으면
// 멀쩡한 기기가 재시작해 버립니다.
// "언제, 얼마나 자는가"의 판단은 power_policy.h 로 옮겼다. PC에서 시험할 수 있고,
// 슬라이스가 워치독 시간을 넘지 못하도록 규칙이 스스로 강제한다.
static vkpower::PolicyConfig powerCfg;

/** 깨어나서 아무 일 없이 다시 잠들기를 반복한 횟수. 이 값이 클수록 더 오래 잔다. */
static uint16_t consecutiveSleeps = 0;
/** 지금까지 실제로 잠들어 있던 시간의 합. 전류계 없이 절전을 확인하는 근거다. */
static uint64_t sleptUsTotal = 0;

// ---------------------------------------------------------------- 핀맵
//  XIAO ESP32-S3 (D 표기 → 실제 GPIO)
//   D0=1  D1=2  D2=3  D3=4  D4=5  D5=6  D6=43(TX) D7=44(RX) D8=7  D9=8  D10=9
//
//  버튼 세 개는 2.0 펌웨어(firmware/legacy/sketch_jul10a_v2.ino)가 쓰던 D0·D1·D2를
//  그대로 씁니다. 이미 납땜해 둔 기기를 뜯지 않고 펌웨어만 올리면 되도록 한 것입니다.
//
//  ※ D2(GPIO3)는 부팅 스트래핑 핀(JTAG 소스 선택)입니다. 전원이 들어오는 순간
//    3번 버튼이 눌려 있으면 스트래핑 값이 바뀔 수 있습니다. 정상 부팅에는 영향이
//    없지만, 기판을 새로 뜬다면 D8(GPIO7)로 옮기는 편이 안전합니다.
//    그때는 아래 배열의 3을 7로만 바꾸면 됩니다.

static const uint8_t BUTTON_COUNT = 3;
static const uint8_t BUTTON_PINS[BUTTON_COUNT] = {
    1,   // D0 — 1번 버튼
    2,   // D1 — 2번 버튼
    3    // D2 — 3번 버튼  (스트래핑 핀 주의, 위 설명 참고)
};
static const uint8_t PROBE_PIN = 9;   // D10 — 지연 측정용 관측 핀
// D3~D10 은 비어 있습니다. 나중에 표시 장치(LED 등)를 붙인다면 여기를 씁니다.

// ---------------------------------------------------------------- 큐 / 상태

/** ISR이 남기는 최소 정보. ISR 안에서는 이것만 하고 즉시 빠져나옵니다. */
struct EdgeMsg {
    uint8_t  index;    // 버튼 번호 - 1
    uint8_t  level;    // 핀 전압 (풀업이므로 0 = 눌림)
    uint32_t stampUs;  // 접점이 바뀐 시각
};

/** 입력 태스크가 확정한 "사람이 의도한 누름" */
struct PressMsg {
    uint8_t  button;   // 1~3
    uint8_t  kind;     // K_SHORT / K_LONG / K_DOUBLE
    uint32_t stampUs;  // 판정 근거가 된 접점 시각 (지연 계산의 기준점)
};

static QueueHandle_t edgeQueue   = nullptr;
static QueueHandle_t pressQueue  = nullptr;

static PressFsm  fsm[BUTTON_COUNT];

/**
 * 주머니·가방 안에서 옷감에 눌린 것을 걸러 냅니다.
 * 걸러 내는 판단을 폰이 아니라 기기가 하는 이유는, 주머니에 들어 있는 동안이
 * 바로 폰이 잠들어 있는 시간이기 때문입니다. (pocket_guard.h)
 */
static vkguard::PocketGuard pocket;

static Decoder   rx;

/** 보고서에 그대로 넣을 수 있는 실측 카운터 */
static struct {
    uint16_t sent;          // 보낸 이벤트 프레임 수
    uint16_t retx;          // 재전송 횟수
    uint16_t ackTimeout;    // 세 번 다 실패한 횟수
    uint16_t lastLatencyUs; // 마지막 접점→송출 지연
    uint16_t maxLatencyUs;  // 최악값
    uint32_t sleeps;        // 절전에 들어간 횟수 (저전력이 실제로 도는지 확인용)
} stats = {0, 0, 0, 0, 0, 0};

/**
 * ISR이 큐에 넣지 못하고 버린 접점 변화 수.
 * 0이 아니면 "버튼을 눌렀는데 반응이 없었다"는 뜻이고, 원인이 큐 크기나
 * 태스크 지연이라는 것까지 알 수 있습니다. 눌린 것을 놓치는 일은
 * 이 기기에서 가장 나쁜 고장이라, 짐작하지 않고 세어 둡니다.
 */
static volatile uint32_t edgeDrops = 0;

/** 전원이 들어온 마지막 이유 (정상 부팅인지, 워치독·전압강하 때문인지) */
static uint8_t bootReason = 0;

// ---------------------------------------------------------------- ISR

/**
 * 버튼 인터럽트. 여기서 하는 일은 "언제·어디서 바뀌었는지"를 큐에 넣는 것뿐입니다.
 * 판단은 전부 태스크에서 합니다 (ISR을 짧게 유지해야 지연이 흔들리지 않습니다).
 */
static void IRAM_ATTR onButtonEdge(void* arg) {
    EdgeMsg msg;
    msg.index   = (uint8_t)(uint32_t)arg;
    msg.level   = (uint8_t)digitalRead(BUTTON_PINS[msg.index]);
    msg.stampUs = micros();

#if VK_LATENCY_PROBE
    // 손을 떼는 순간(풀업이 다시 HIGH로 올라오는 접점 = 짧게 누름이 확정되는 시점)에
    // 관측 핀을 올리고, 프레임을 다 보낸 뒤 내립니다. 로직 애널라이저에서 이 펄스
    // 폭을 그대로 읽으면 "버튼 접점 → USB 프레임 송출" 지연이 됩니다.
    if (msg.level == HIGH) {
        digitalWrite(PROBE_PIN, HIGH);
    }
#endif

    BaseType_t woken = pdFALSE;
    if (xQueueSendFromISR(edgeQueue, &msg, &woken) != pdTRUE) {
        // 큐가 가득 참 = 접점 변화를 놓쳤다는 뜻.
        // volatile 에 ++ 를 쓰면 읽기·쓰기 순서가 규정되지 않아 C++20에서 폐기됐습니다.
        // 이 값은 쓰는 쪽이 ISR 하나뿐이고 읽는 쪽은 태스크뿐이라, 읽고 더해 쓰는
        // 세 단계를 눈에 보이게 적어 두는 편이 의도도 분명합니다.
        uint32_t dropped = edgeDrops;
        edgeDrops = dropped + 1;
    }
    if (woken == pdTRUE) {
        portYIELD_FROM_ISR();
    }
}

// ---------------------------------------------------------------- 입력 태스크

/**
 * 확정된 누름을 폰으로 내보내도 되는지 마지막으로 한 번 더 봅니다.
 *
 * 상태머신(press_fsm.h)은 "이 접점이 사람이 의도한 한 번의 누름인가"를 봅니다.
 * 여기서는 그보다 넓게 "이 상황 자체가 사람이 누르는 상황인가"를 봅니다.
 * 옷감은 여러 개를 한꺼번에, 오래, 여러 번 누릅니다. (pocket_guard.h)
 *
 * 걸러 낸 것은 세어 두었다가 STATS2 로 앱에 보고합니다. 사용자가
 * "왜 안 눌렸지?" 하고 물을 때 답할 근거가 됩니다.
 */
static bool allowPress(uint8_t button, uint32_t nowMs) {
    return pocket.judge(button, nowMs) == vkguard::ALLOW;
}

static void inputTask(void* /*arg*/) {
    esp_task_wdt_add(nullptr);          // 이 태스크를 워치독 감시 대상에 넣습니다
    EdgeMsg edge;
    for (;;) {
        esp_task_wdt_reset();           // "아직 살아 있습니다"
        // 1) ISR이 쌓아 둔 접점 변화를 모두 처리
        while (xQueueReceive(edgeQueue, &edge, 0) == pdTRUE) {
            uint8_t kind;
            bool down = (edge.level == LOW);          // 풀업: LOW = 눌림

            // 상태머신은 ms 로 판단하는데 ISR은 us 로 찍습니다. micros()는 약 71분마다
            // 한 바퀴 돌아 millis()와 어긋나므로, "지금으로부터 얼마나 지난 접점인지"를
            // 빼는 방식으로 옮깁니다(부호 없는 뺄셈이라 자릿수가 넘어가도 정확합니다).
            uint32_t ageMs  = (uint32_t)(micros() - edge.stampUs) / 1000;
            uint32_t edgeMs = millis() - ageMs;

            // 주머니 판정기에도 접점 변화를 그대로 알려 줍니다.
            // (두 개가 동시에 눌렸는지, 얼마나 오래 눌려 있는지를 여기서 셉니다)
            if (down) {
                pocket.onDown((uint8_t)(edge.index + 1), edgeMs);
            } else {
                pocket.onUp((uint8_t)(edge.index + 1), edgeMs);
            }

            if (fsm[edge.index].onEdge(down, edgeMs, kind)
                    && allowPress((uint8_t)(edge.index + 1), edgeMs)) {
                PressMsg press = {(uint8_t)(edge.index + 1), kind, edge.stampUs};
                xQueueSend(pressQueue, &press, 0);
            }
        }

        // 2) 누르고 있는 동안의 "길게 누름" 확정
        uint32_t nowMs = millis();
        for (uint8_t i = 0; i < BUTTON_COUNT; i++) {
            uint8_t kind;
            if (fsm[i].tick(nowMs, kind) && allowPress((uint8_t)(i + 1), nowMs)) {
                PressMsg press = {(uint8_t)(i + 1), kind, micros()};
                xQueueSend(pressQueue, &press, 0);
            }
        }

        vTaskDelay(pdMS_TO_TICKS(10));
    }
}

// ---------------------------------------------------------------- 프로토콜 태스크

static uint8_t nextSeq() {
    static uint8_t seq = 0;
    seq++;
    if (seq == 0) {
        seq = 1;                                  // 0은 "시퀀스 없음"이라 건너뜁니다
    }
    return seq;
}

static void writeFrame(uint8_t seq, uint8_t type, const uint8_t* payload, uint8_t len) {
    uint8_t buf[MAX_FRAME];
    uint8_t n = encode(seq, type, payload, len, buf);
    if (n == 0) {
        return;
    }
    Serial.write(buf, n);
    Serial.flush();
}

// ---------------------------------------------------------------- 버튼 매핑 저장
//
// "1번 버튼 = 카카오톡" 은 지금까지 폰에만 있었습니다. 그래서 폰을 바꾸거나 앱을
// 다시 깔면 어르신이 처음부터 다시 정하셔야 했습니다. 그 부담은 기기를 새로 받는
// 것과 다르지 않습니다. 이제 앱이 매핑을 정하면(직접 고르시든, AI가 정하든)
// 그 결과를 기기 플래시(NVS)에도 같이 적습니다. 새 폰에서는 기기가 먼저 알려 줍니다.
//
// 저장 형식은 슬롯마다 "패키지명\n보여 줄 이름" 한 줄입니다.
// 16바이트 payload 한도를 넘으므로 조각으로 나눠 주고받습니다. (slot_store.h)

static Preferences      nvs;
static vkmap::Entry     slotMap[vkmap::MAX_SLOTS];
static vkmap::Assembler mapRx;

static const char* NVS_NAMESPACE = "vibekey";

static void nvsKeyFor(uint8_t slot, char* out) {
    out[0] = 's';
    out[1] = 'l';
    out[2] = 'o';
    out[3] = 't';
    out[4] = (char)('0' + slot);
    out[5] = '\0';
}

/** 부팅할 때 플래시에 적어 둔 매핑을 읽어 옵니다. */
static void loadSlotMap() {
    if (!nvs.begin(NVS_NAMESPACE, /*readOnly=*/true)) {
        return;                      // 아직 한 번도 저장한 적이 없습니다
    }
    for (uint8_t slot = 1; slot <= vkmap::MAX_SLOTS; slot++) {
        char key[8];
        nvsKeyFor(slot, key);
        char buf[vkmap::MAX_VALUE];
        size_t n = nvs.getBytes(key, buf, sizeof(buf));
        if (n > 0 && n <= vkmap::MAX_VALUE) {
            slotMap[slot - 1].set(buf, (uint8_t)n);
        }
    }
    nvs.end();
}

/** 슬롯 하나를 플래시에 적습니다. 값이 그대로면 쓰지 않습니다(플래시 수명). */
static void saveSlot(uint8_t slot, const vkmap::Entry& entry) {
    if (slot < 1 || slot > vkmap::MAX_SLOTS) {
        return;
    }
    vkmap::Entry& current = slotMap[slot - 1];
    if (current.len == entry.len &&
        memcmp(current.text, entry.text, entry.len) == 0) {
        return;                      // 같은 값이면 플래시를 건드리지 않습니다
    }
    current = entry;

    if (!nvs.begin(NVS_NAMESPACE, /*readOnly=*/false)) {
        return;
    }
    char key[8];
    nvsKeyFor(slot, key);
    if (entry.len == 0) {
        nvs.remove(key);
    } else {
        nvs.putBytes(key, entry.text, entry.len);
    }
    nvs.end();
}

static void sendHello() {
    // caps: 기기가 가진 기능 비트. CAP_SLOT_STORE 는 "버튼 매핑을 내가 들고 있을 수 있다"는 뜻입니다.
    // 앱은 이 비트를 보고, 새 폰에서 매핑을 되찾아 갈지 정합니다.
    // 마지막 바이트는 "이 기기가 마지막으로 왜 재시작했는지" 입니다. 정상 부팅인지,
    // 워치독이 물었는지, 전압이 떨어졌는지를 앱의 자가진단 화면에서 볼 수 있습니다.
    uint8_t p[6] = {PROTO_VERSION, FW_MAJOR, FW_MINOR, BUTTON_COUNT, CAP_SLOT_STORE, bootReason};
    writeFrame(0, T_HELLO, p, sizeof(p));
}

/**
 * 저장해 둔 매핑을 조각내어 폰에 보냅니다.
 * 폰이 GET_MAP 을 보냈을 때, 그리고 저장이 끝났을 때(확인 회신) 씁니다.
 *
 * SEQ 는 0(답을 기다리지 않는 알림)으로 보냅니다. 이 값은 놓쳐도 어르신이 손해를
 * 보지 않고, 폰이 다시 물어보면 되기 때문에 재전송 대기열을 차지하지 않게 했습니다.
 */
static void sendSlotMap(uint8_t slot) {
    if (slot < 1 || slot > vkmap::MAX_SLOTS) {
        return;
    }
    const vkmap::Entry& entry = slotMap[slot - 1];
    uint8_t total = vkmap::chunkCount(entry.len);
    for (uint8_t index = 0; index < total; index++) {
        uint8_t p[MAX_PAYLOAD];
        uint8_t n = vkmap::buildChunk(slot, entry, index, p);
        if (n == 0) {
            return;
        }
        writeFrame(0, T_MAP, p, n);
    }
}

static void sendAllSlotMaps() {
    for (uint8_t slot = 1; slot <= vkmap::MAX_SLOTS; slot++) {
        sendSlotMap(slot);
    }
}

// 폰이 없는 동안 눌린 것을 담아 둡니다. protoTask 만 건드리므로 잠금이 필요 없습니다.
static vkbuf::PressBuffer offline;

static void sendStats() {
    uint16_t crcErr  = (uint16_t)rx.crcErrors;
    uint16_t up      = (uint16_t)(millis() / 1000);
    uint16_t freeKb  = (uint16_t)(esp_get_free_heap_size() / 1024);
    uint16_t minKb   = (uint16_t)(esp_get_minimum_free_heap_size() / 1024);
    // "놓친 입력" = ISR 큐에서 흘린 것 + 오프라인 버퍼에서 밀려나거나 만료된 것.
    // 사용자 입장에서는 둘 다 "눌렀는데 아무 일도 안 일어난" 경우입니다.
    uint32_t missedAll = edgeDrops + offline.missed();
    uint16_t drops   = (uint16_t)(missedAll > 0xFFFF ? 0xFFFF : missedAll);

    // payload 16바이트 = 규격 최대치. 더 넣으려면 종류를 나눠야 합니다.
    uint8_t p[16] = {
        (uint8_t)(stats.sent & 0xFF),        (uint8_t)(stats.sent >> 8),
        (uint8_t)(stats.retx & 0xFF),        (uint8_t)(stats.retx >> 8),
        (uint8_t)(stats.ackTimeout & 0xFF),  (uint8_t)(stats.ackTimeout >> 8),
        (uint8_t)(crcErr & 0xFF),            (uint8_t)(crcErr >> 8),
        (uint8_t)(up & 0xFF),                (uint8_t)(up >> 8),
        (uint8_t)(freeKb & 0xFF),            (uint8_t)(freeKb >> 8),
        (uint8_t)(minKb & 0xFF),             (uint8_t)(minKb >> 8),
        (uint8_t)(drops & 0xFF),             (uint8_t)(drops >> 8)
    };
    writeFrame(0, T_STATS, p, sizeof(p));
}


/**
 * 주머니로 보고 걸러 낸 집계를 보냅니다.
 *
 * STATS 의 payload 는 이미 규격 최대치(16바이트)라 더 얹을 자리가 없어
 * 종류를 하나 나눴습니다. 이 숫자가 있어야 사용자가 "왜 안 눌렸지?"라고
 * 물었을 때 앱이 "주머니에서 눌린 것으로 보아 N번 걸렀어요"라고 답할 수 있습니다.
 */
static void sendGuardStats() {
    uint16_t total = pocket.blockedTotal();
    uint16_t multi = pocket.blockedMulti();
    uint16_t stuck = pocket.blockedStuck();
    uint16_t burst = (uint16_t)(pocket.blockedBurst() + pocket.blockedCooldown());

    // 절전 실측치. 전류를 추정하지 않는다 — 잰 것은 "잠들어 있던 시간"뿐이다.
    uint64_t upUs   = (uint64_t)esp_timer_get_time();
    uint16_t duty   = vkpower::dutyPermille(sleptUsTotal, upUs);
    uint16_t sleeps = (uint16_t)(stats.sleeps > 0xFFFF ? 0xFFFF : stats.sleeps);

    uint8_t p[12] = {
        (uint8_t)(total & 0xFF),  (uint8_t)(total >> 8),
        (uint8_t)(multi & 0xFF),  (uint8_t)(multi >> 8),
        (uint8_t)(stuck & 0xFF),  (uint8_t)(stuck >> 8),
        (uint8_t)(burst & 0xFF),  (uint8_t)(burst >> 8),
        (uint8_t)(duty & 0xFF),   (uint8_t)(duty >> 8),
        (uint8_t)(sleeps & 0xFF), (uint8_t)(sleeps >> 8)
    };
    writeFrame(0, T_STATS2, p, sizeof(p));
}

/** 버튼이 하나라도 눌려 있으면 true (INPUT_PULLUP 이라 눌림 = LOW) */
static bool anyButtonDown() {
    for (uint8_t i = 0; i < BUTTON_COUNT; i++) {
        if (digitalRead(BUTTON_PINS[i]) == LOW) {
            return true;
        }
    }
    return false;
}

/**
 * 조건이 맞으면 아주 잠깐 잠듭니다.
 * @return 실제로 잠들었으면 true
 */
static bool maybeLightSleep(bool linkUp, uint32_t lastActivityMs) {
    vkpower::Decision d = vkpower::decide(
            powerCfg,
            linkUp,
            offline.empty(),
            anyButtonDown(),
            (uint32_t)(millis() - lastActivityMs),
            consecutiveSleeps);

    if (!d.sleep) {
        consecutiveSleeps = 0;      // 뭔가 하고 있으니 다음엔 다시 짧게 잔다
        return false;
    }

    // 버튼이 눌리는 순간(LOW) 깨어난다. 사람이 기기를 다시 쓰는 순간은 이걸로 잡히므로,
    // 슬라이스가 길어져도 사용자가 기다리는 일은 생기지 않는다.
    for (uint8_t i = 0; i < BUTTON_COUNT; i++) {
        gpio_wakeup_enable((gpio_num_t)BUTTON_PINS[i], GPIO_INTR_LOW_LEVEL);
    }
    esp_sleep_enable_gpio_wakeup();
    esp_sleep_enable_timer_wakeup(d.sliceUs);
    // USB 활동으로 깨우는 기능은 이 칩·코어 조합에서 쓸 수 없다.
    // (esp_sleep_enable_usb_wakeup 은 고속 USB-OTG 전용이고, ESP32-S3 의 CDC 는
    //  USB Serial/JTAG 라 해당하지 않는다.)
    // 그래서 타이머 깨움이 그 역할을 한다 — 깨어나 DTR 을 다시 본다.

    int64_t before = esp_timer_get_time();
    esp_light_sleep_start();
    int64_t slept = esp_timer_get_time() - before;
    if (slept > 0) {
        sleptUsTotal += (uint64_t)slept;
    }

    // 깨움 설정을 되돌린다. 남겨 두면 다음 판단에 영향을 준다.
    for (uint8_t i = 0; i < BUTTON_COUNT; i++) {
        gpio_wakeup_disable((gpio_num_t)BUTTON_PINS[i]);
    }
    esp_sleep_disable_wakeup_source(ESP_SLEEP_WAKEUP_TIMER);

    stats.sleeps++;
    if (consecutiveSleeps < 0xFFFF) {
        consecutiveSleeps++;        // 계속 조용하면 다음엔 더 오래 잔다
    }
    return true;
}

static void protoTask(void* /*arg*/) {
    PressMsg  pending;                 // ACK를 기다리는 중인 이벤트
    bool      waitingAck = false;
    uint8_t   pendingSeq = 0;
    uint8_t   retry      = 0;
    uint32_t  sentAtMs   = 0;
    bool      linkUp     = false;
    uint32_t  lastActive = millis();   // 마지막으로 뭔가 일어난 시각

    esp_task_wdt_add(nullptr);
    for (;;) {
        esp_task_wdt_reset();
        // --- 1. 폰이 연결됐는지 (USB CDC의 DTR) ---
        bool nowUp = (bool)Serial;
        if (nowUp != linkUp) {
            linkUp = nowUp;
            lastActive = millis();
            if (linkUp) {
                rx.reset();
                sendHello();
                // 끊겨 있는 동안 눌린 것이 있으면 이제부터 하나씩 되살립니다.
            }
        }

        // --- 2. 폰이 보낸 바이트 해석 ---
        Frame f;
        while (Serial.available() > 0) {
            uint8_t b = (uint8_t)Serial.read();
            if (!rx.push(b, f)) {
                continue;
            }
            switch (f.type) {
                case T_ACK:
                    if (waitingAck && f.u8(0) == pendingSeq) {
                        waitingAck = false;         // 잘 도착했음 — 재전송 중단
                    }
                    break;
                case T_PING:
                    sendHello();
                    sendStats();
                    sendGuardStats();
                    break;
                case T_GET_MAP:
                    // 새 폰이 "네가 들고 있는 설정을 알려 줘"라고 물었습니다.
                    sendAllSlotMaps();
                    break;
                case T_SET_MAP: {
                    // 조각을 모읍니다. 다 모이면 플래시에 적고 그대로 되읽어 보냅니다.
                    // 되읽어 보내는 것이 곧 "잘 저장됐다"는 확인이라, 따로 ACK를 두지 않았습니다.
                    uint8_t slot = f.u8(0);
                    vkmap::Result r = mapRx.accept(slot, f.u8(1), f.u8(2),
                                                   f.payload + vkmap::HEADER_LEN,
                                                   f.len > vkmap::HEADER_LEN
                                                       ? (uint8_t)(f.len - vkmap::HEADER_LEN) : 0);
                    if (r == vkmap::COMPLETE) {
                        saveSlot(slot, mapRx.completed(slot));
                        sendSlotMap(slot);
                    }
                    break;
                }
                default:
                    break;
            }
        }

        // --- 2.5 폰이 없는 동안 눌린 것은 보내지 말고 담아 둡니다 ---
        //     예전에는 그대로 내보내고 ACK 를 세 번 기다리다 버렸습니다.
        //     그동안 다음 입력이 큐에 쌓여 결국 사라졌습니다.
        if (!linkUp) {
            PressMsg m;
            while (xQueueReceive(pressQueue, &m, 0) == pdTRUE) {
                offline.push(m.button, m.kind, millis());
            }
        }

        // --- 3. 확정된 누름을 프레임으로 내보내기 ---
        //     담아 둔 것이 있으면 그것을 먼저 비웁니다. 누른 순서를 지킵니다.
        bool haveEvent = false;
        if (!waitingAck && linkUp && !offline.empty()) {
            vkbuf::Item it;
            if (offline.popReplayable(millis(), it)) {
                pending.button  = it.button;
                pending.kind    = it.kind;          // REPLAY_FLAG 가 세워져 있습니다
                pending.stampUs = micros();         // 되살린 것의 지연은 의미가 없습니다
                haveEvent = true;
            }
        }
        if (!waitingAck && !haveEvent && xQueueReceive(pressQueue, &pending, 0) == pdTRUE) {
            haveEvent = true;
        }
        if (haveEvent) {
            lastActive = millis();
            uint32_t latency = micros() - pending.stampUs;
            if (latency > 65535) {
                latency = 65535;
            }
            stats.lastLatencyUs = (uint16_t)latency;
            if (latency > stats.maxLatencyUs) {
                stats.maxLatencyUs = (uint16_t)latency;
            }

            uint8_t p[4] = {pending.button, pending.kind,
                            (uint8_t)(latency & 0xFF), (uint8_t)(latency >> 8)};
            pendingSeq = nextSeq();
            writeFrame(pendingSeq, T_EVT_PRESS, p, sizeof(p));

#if VK_LATENCY_PROBE
            digitalWrite(PROBE_PIN, LOW);   // 관측 핀 펄스 끝 = 송출 완료
#endif
            stats.sent++;
            waitingAck = true;
            retry = 0;
            sentAtMs = millis();
        }

        // --- 4. ACK가 안 오면 다시 보내고, 세 번 다 실패하면 횟수를 기록 ---
        if (waitingAck && (uint32_t)(millis() - sentAtMs) >= ACK_TIMEOUT_MS) {
            if (retry < MAX_RETRY - 1) {
                retry++;
                stats.retx++;
                uint32_t latency = stats.lastLatencyUs;
                uint8_t p[4] = {pending.button, pending.kind,
                                (uint8_t)(latency & 0xFF), (uint8_t)(latency >> 8)};
                writeFrame(pendingSeq, T_EVT_PRESS, p, sizeof(p));  // SEQ 그대로 = 폰이 중복 실행 안 함
                sentAtMs = millis();
            } else {
                // 세 번을 다 보냈는데도 답이 없습니다. 기기에는 알릴 방법이 없으므로
                // 횟수만 세어 두고, 앱의 자가진단 화면이 STATS로 읽어 가게 합니다.
                waitingAck = false;
                stats.ackTimeout++;
                // 답이 없다는 것은 앱이 죽었다는 뜻입니다. 버리지 말고 담아 둡니다.
                if (!(pending.kind & vkbuf::REPLAY_FLAG)) {
                    offline.push(pending.button, pending.kind, millis());
                }
            }
        }

        // --- 5. 할 일이 없고 폰도 안 듣고 있으면 잠깐 잠듭니다 ---
        if (!waitingAck && maybeLightSleep(linkUp, lastActive)) {
            continue;   // 깨어나자마자 워치독부터 먹입니다
        }

        vTaskDelay(pdMS_TO_TICKS(5));
    }
}

// ---------------------------------------------------------------- setup / loop

void setup() {
    // 전원이 들어온 이유를 가장 먼저 기록합니다. 앱이 HELLO로 받아 갑니다.
    bootReason = (uint8_t)esp_reset_reason();

    Serial.begin(115200);
#if ARDUINO_USB_CDC_ON_BOOT
    // 폰이 안 꽂혀 있을 때 Serial.write가 무한정 붙들리지 않게 합니다.
    // (이게 없으면 케이블을 뺀 채 버튼을 누를 때 태스크가 멈춰 버립니다.)
    Serial.setTxTimeoutMs(20);
#endif

    for (uint8_t i = 0; i < BUTTON_COUNT; i++) {
        pinMode(BUTTON_PINS[i], INPUT_PULLUP);
    }
#if VK_LATENCY_PROBE
    pinMode(PROBE_PIN, OUTPUT);
    digitalWrite(PROBE_PIN, LOW);
#endif

    // 태스크 워치독. 아두이노 코어가 이미 켜 둔 경우가 있어 init이 거부되면
    // reconfigure로 시간만 우리 값으로 바꿉니다.
    esp_task_wdt_config_t wdtCfg = {};
    wdtCfg.timeout_ms     = WDT_TIMEOUT_MS;
    wdtCfg.idle_core_mask = 0;          // idle 태스크는 감시하지 않습니다
    wdtCfg.trigger_panic  = true;       // 물리면 패닉 → 자동 재시작
    if (esp_task_wdt_init(&wdtCfg) == ESP_ERR_INVALID_STATE) {
        esp_task_wdt_reconfigure(&wdtCfg);
    }

    // 폰이 듣고 있는 동안에는 잘 수 없다(자면 들어오는 바이트가 사라진다).
    // 대신 할 일이 없을 때 CPU 주파수를 240MHz 에서 80MHz 로 스스로 내리게 한다.
    // 이건 자는 게 아니라 느려지는 것이라, USB CDC 는 그대로 살아 있다.
    //
    // 자동 light sleep 은 켜지 않는다 — 그걸 켜면 코어가 임의의 시점에 잠들어
    // USB 수신이 깨진다. 우리가 자는 시점은 power_policy.h 가 직접 고른다.
    {
        esp_pm_config_t pm = {};
        pm.max_freq_mhz = 240;
        pm.min_freq_mhz = 80;
        pm.light_sleep_enable = false;
        // 코어가 전력 관리 없이 빌드된 경우 ESP_ERR_NOT_SUPPORTED 가 온다.
        // 그때는 그냥 240MHz 로 도는 것이고, 기능에는 영향이 없다.
        esp_pm_configure(&pm);
    }

    // 플래시에 적어 둔 버튼 매핑을 먼저 읽어 둡니다. 폰이 물어보면 바로 답할 수 있게.
    loadSlotMap();

    edgeQueue   = xQueueCreate(32, sizeof(EdgeMsg));
    pressQueue  = xQueueCreate(8,  sizeof(PressMsg));

    // 두 태스크 모두 core 1에 올립니다 (core 0은 USB·시스템이 씁니다).
    xTaskCreatePinnedToCore(inputTask,  "input",  3072, nullptr, 4, nullptr, 1);
    xTaskCreatePinnedToCore(protoTask,  "proto",  4096, nullptr, 3, nullptr, 1);

    // 큐와 태스크가 준비된 뒤에 인터럽트를 붙입니다(먼저 붙이면 큐가 없어 죽습니다).
    for (uint8_t i = 0; i < BUTTON_COUNT; i++) {
        attachInterruptArg(digitalPinToInterrupt(BUTTON_PINS[i]),
                           onButtonEdge, (void*)(uint32_t)i, CHANGE);
    }
}

void loop() {
    // 실제 일은 전부 태스크가 합니다. 기본 loop 태스크는 잠들어 있게 둡니다.
    vTaskDelay(pdMS_TO_TICKS(1000));
}
