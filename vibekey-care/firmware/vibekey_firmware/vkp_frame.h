/*
 * vkp_frame.h — 바이브키 프레임 프로토콜(VKP v1)
 *
 * 왜 평문("True\n")을 버렸는가
 * ---------------------------------------------------------------------------
 * 바이브키 케어는 사용자가 화면을 보지 않은 채 누르는 기기입니다. 눈으로 확인하는
 * 단계가 없으므로 잘못 실행되어도 사용자가 그 자리에서 알아채지 못합니다. 확인하는
 * 사람이 없는 입력이라, 정확성을 신호 단계에서 보장해야 합니다. (예를 들어 택시
 * 호출은 요금이 발생하고, 되돌리려면 결국 화면을 열어야 합니다.)
 * 그런데 평문 줄바꿈 방식에는
 *   - 프레임 경계가 없고(어디부터 어디까지가 한 메시지인지 모름)
 *   - 오류 검출이 없고(비트 하나가 뒤집혀도 그대로 실행)
 *   - 중복 방지가 없습니다(같은 신호가 두 번 오면 두 번 실행)
 * 세 가지가 모두 빠져 있습니다. 그래서 아래 형식으로 바꿨습니다.
 *
 *   0xAA | SEQ | TYPE | LEN | PAYLOAD[LEN] | CRC16_L | CRC16_H | 0x55
 *    (1)   (1)   (1)    (1)     (0~16)         (1)       (1)      (1)   = 7 + LEN 바이트
 *
 *   CRC16: SEQ·TYPE·LEN·PAYLOAD 위에서 계산 (CRC-16/CCITT-FALSE, 0x1021, 초기값 0xFFFF)
 *          리틀엔디언으로 두 바이트를 붙입니다.
 *   SEQ  : 1~255 순환(0은 "시퀀스 없음"). 폰은 같은 SEQ를 두 번 실행하지 않습니다.
 *   ETX  : CRC를 통과해도 끝 바이트가 어긋나면 버립니다(이중 확인).
 *
 * 왜 CRC8이 아니라 CRC16인가 — 재 보고 정했습니다
 * ---------------------------------------------------------------------------
 * 처음에는 CRC8(0x07)로 만들었습니다. 프레임 200만 개에 무작위 손상을 먹여
 * "깨진 프레임이 정상 버튼 누름으로 새어 나가는 비율"을 두 조건에서 재 봤습니다.
 *   (실험 코드: firmware/test/test_vkp.cpp — 난수 수열을 직접 들고 다녀
 *    어떤 컴파일러에서 돌려도 아래와 똑같은 숫자가 나옵니다)
 *
 *   1~3비트 손상(전선 잡음 수준)          CRC8    0건   CRC16   0건
 *   4~10비트 손상(커넥터 흔들림·전원 튐)   CRC8  204건   CRC16   9건
 *                                            (0.0102%)    (0.0004%)   ← 약 23배
 *
 * 검사값이 우연히 들어맞는 일은 확률이지 0이 아닙니다. 그래서 "0건"이라고 적지 않고
 * 잰 값을 그대로 적습니다. CRC8이 부실한 것도 아닙니다 — 여러 비트가 한꺼번에
 * 망가지는 상황에서만 두 설계가 갈립니다. 그래도 올린 이유는 이 기기가 화면 확인
 * 없이 동작하기 때문입니다. 새어 나간 한 건을 사용자가 알아챌 방법이 없습니다.
 * 치른 비용은 프레임당 1바이트(버튼 이벤트 10바이트 → 11바이트)뿐이고,
 * 계산 시간은 240MHz에서 10us 아래입니다.
 *
 * 이 헤더는 Arduino 헤더를 하나도 쓰지 않는 순수 C++ 입니다.
 * 그래서 PC에서 그대로 컴파일해 단위 테스트할 수 있습니다.
 *   → firmware/test/test_vkp.cpp , firmware/test/run_tests.sh
 * 안드로이드 쪽 FrameCodec.java 와 한 글자도 다르지 않은 규격입니다.
 */
#ifndef VKP_FRAME_H
#define VKP_FRAME_H

#include <stdint.h>
#include <stddef.h>
#include <string.h>

namespace vkp {

// ---------------------------------------------------------------- 상수

static const uint8_t  STX          = 0xAA;
static const uint8_t  ETX          = 0x55;
static const uint8_t  MAX_PAYLOAD  = 16;
static const uint8_t  OVERHEAD     = 7;                       // STX+SEQ+TYPE+LEN+CRC(2)+ETX
static const uint8_t  MAX_FRAME    = MAX_PAYLOAD + OVERHEAD;  // 23
static const uint8_t  PROTO_VERSION = 1;

// 기기 → 폰
static const uint8_t T_EVT_PRESS = 0x01;  // btn, kind, latencyLo, latencyHi
static const uint8_t T_HELLO     = 0x02;  // proto, fwMajor, fwMinor, buttons, caps
static const uint8_t T_STATS     = 0x03;  // sent, retx, ackTimeout, crcErr, uptimeSec (각 2바이트 LE)
static const uint8_t T_MAP       = 0x04;  // slot, index, count, data[..] — 기기에 저장된 버튼 매핑
static const uint8_t T_STATS2    = 0x05;  // 주머니 차단 + 절전 실측 (blocked, multi, stuck, burst, dutyPermille, sleeps · 각 2바이트 LE)

// 폰 → 기기
static const uint8_t T_ACK       = 0x10;  // ackSeq
static const uint8_t T_PING      = 0x12;  // (빈 payload) → 기기가 HELLO+STATS로 답함
// 0x11 은 예전에 진동 패턴 지시(FEEDBACK)로 쓰던 번호입니다. 기기에 출력 장치가
// 없어 지금은 쓰지 않지만, 나중에 표시 장치를 붙일 때를 위해 비워 둡니다.

static const uint8_t T_SET_MAP   = 0x13;  // slot, index, count, data[..] — 이 버튼 매핑을 저장해 둬
static const uint8_t T_GET_MAP   = 0x14;  // (빈 payload) → 기기가 저장해 둔 매핑을 T_MAP 으로 답함

// caps 비트 (HELLO 의 5번째 바이트)
static const uint8_t CAP_SLOT_STORE = 0x01;  // 버튼 매핑을 기기에 저장할 수 있음

// 누름 종류
static const uint8_t K_SHORT  = 0;
static const uint8_t K_LONG   = 1;
static const uint8_t K_DOUBLE = 2;

// ---------------------------------------------------------------- CRC16

/**
 * CRC-16/CCITT-FALSE (다항식 0x1021, 초기값 0xFFFF).
 * 표(512바이트)를 두지 않고 비트로 도는 방식이라 RAM을 쓰지 않습니다.
 * 프레임 하나(최대 23바이트) 계산은 ESP32-S3(240MHz)에서 10us를 넘지 않습니다.
 */
inline uint16_t crc16(const uint8_t* data, size_t len) {
    uint16_t crc = 0xFFFF;
    for (size_t i = 0; i < len; i++) {
        crc ^= (uint16_t)data[i] << 8;
        for (uint8_t b = 0; b < 8; b++) {
            crc = (crc & 0x8000) ? (uint16_t)((crc << 1) ^ 0x1021) : (uint16_t)(crc << 1);
        }
    }
    return crc;
}

// ---------------------------------------------------------------- 한 프레임

struct Frame {
    uint8_t seq;
    uint8_t type;
    uint8_t len;
    uint8_t payload[MAX_PAYLOAD];

    uint8_t u8(uint8_t i)  const { return i < len ? payload[i] : 0; }
    /** 리틀엔디언 2바이트 읽기 */
    uint16_t u16(uint8_t i) const {
        return (uint16_t)(u8(i) | ((uint16_t)u8((uint8_t)(i + 1)) << 8));
    }
};

/**
 * 프레임을 바이트로 만듭니다.
 * @return 만들어진 길이(7+len). payload가 너무 길면 0.
 */
inline uint8_t encode(uint8_t seq, uint8_t type,
                      const uint8_t* payload, uint8_t len, uint8_t* out) {
    if (len > MAX_PAYLOAD) {
        return 0;
    }
    uint8_t n = 0;
    out[n++] = STX;
    out[n++] = seq;
    out[n++] = type;
    out[n++] = len;
    for (uint8_t i = 0; i < len; i++) {
        out[n++] = payload[i];
    }
    uint16_t crc = crc16(out + 1, (size_t)(3 + len));   // SEQ부터 PAYLOAD 끝까지
    out[n++] = (uint8_t)(crc & 0xFF);
    out[n++] = (uint8_t)(crc >> 8);
    out[n++] = ETX;
    return n;
}

// ---------------------------------------------------------------- 디코더

/**
 * 바이트를 한 개씩 넣으면 온전한 프레임이 완성될 때 true를 돌려 줍니다.
 *
 * 시리얼은 언제든 중간부터 끊겨 들어올 수 있으므로(케이블을 꽂는 순간 등),
 * 앞머리가 깨지면 한 바이트씩 밀면서 다음 0xAA를 다시 찾습니다(재동기화).
 * 버린 바이트 수를 세어 두기 때문에 "프레임 오류율"을 숫자로 보고할 수 있습니다.
 */
class Decoder {
public:
    Decoder() : crcErrors(0), framingErrors(0), discarded(0), accepted(0), n_(0) {}

    bool push(uint8_t b, Frame& out) {
        if (n_ < MAX_FRAME) {
            buf_[n_++] = b;
        } else {
            // 여기까지 왔다는 건 앞머리가 0xAA인데 끝이 안 맞는 경우입니다. 한 칸 밀고 재시도.
            shift(1);
            buf_[n_++] = b;
        }
        return scan(out);
    }

    void reset() { n_ = 0; }

    uint32_t crcErrors;      // CRC가 안 맞아서 버린 프레임 수
    uint32_t framingErrors;  // 길이/끝바이트가 틀려서 버린 프레임 수
    uint32_t discarded;      // 프레임 밖에서 버려진 바이트 수 (잡음·옛 평문)
    uint32_t accepted;       // 정상적으로 받은 프레임 수

private:
    uint8_t buf_[MAX_FRAME];
    uint8_t n_;

    void shift(uint8_t count) {
        if (count >= n_) {
            n_ = 0;
            return;
        }
        memmove(buf_, buf_ + count, (size_t)(n_ - count));
        n_ = (uint8_t)(n_ - count);
    }

    bool scan(Frame& out) {
        while (n_ > 0) {
            if (buf_[0] != STX) {          // 시작 바이트가 아니면 버림
                discarded++;
                shift(1);
                continue;
            }
            if (n_ < 4) {
                return false;              // 헤더가 아직 덜 왔음
            }
            uint8_t len = buf_[3];
            if (len > MAX_PAYLOAD) {       // 길이가 말이 안 됨 → 가짜 0xAA였음
                framingErrors++;
                discarded++;
                shift(1);
                continue;
            }
            uint8_t total = (uint8_t)(len + OVERHEAD);
            if (n_ < total) {
                return false;              // 본문이 아직 덜 왔음
            }
            uint16_t crc = crc16(buf_ + 1, (size_t)(3 + len));
            if (buf_[total - 3] != (uint8_t)(crc & 0xFF) ||
                buf_[total - 2] != (uint8_t)(crc >> 8)) {
                crcErrors++;
                discarded++;
                shift(1);
                continue;
            }
            if (buf_[total - 1] != ETX) {
                framingErrors++;
                discarded++;
                shift(1);
                continue;
            }
            out.seq  = buf_[1];
            out.type = buf_[2];
            out.len  = len;
            memcpy(out.payload, buf_ + 4, len);
            shift(total);
            accepted++;
            return true;
        }
        return false;
    }
};

}  // namespace vkp

#endif  // VKP_FRAME_H
