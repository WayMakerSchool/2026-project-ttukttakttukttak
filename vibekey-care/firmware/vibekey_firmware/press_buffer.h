#pragma once
//
// 폰이 없을 때 눌린 버튼을 잠깐 담아 두는 고리 버퍼입니다.
//
// 왜 필요한가
//   USB가 빠졌거나 앱이 죽어 있는 동안 버튼을 누르면, 지금까지는 그 입력이
//   그냥 사라졌습니다. README 가 스스로 "가장 나쁜 고장"이라고 부른 상황입니다.
//
// 왜 무작정 다시 보내지 않는가
//   10분 전에 누른 "전화"가 재연결되는 순간 갑자기 걸리면 그건 고장보다
//   나쁩니다. 그래서 나이 제한을 둡니다.
//
//     · REPLAY_MAX_AGE_MS 안쪽 : 다시 보냅니다. 사용자가 방금 누른 것이고,
//                                아직 그 일이 일어나기를 기대하고 있습니다.
//     · 그보다 오래된 것       : 보내지 않고 "놓친 입력" 수만 세어 둡니다.
//                                앱의 자가진단 화면이 STATS 로 읽어 갑니다.
//
//   되살린 이벤트는 kind 의 최상위 비트(REPLAY_FLAG)를 세워 보냅니다.
//   앱은 그것을 보고 "조금 전에 누르신 것입니다" 라고 알려 줄 수 있습니다.
//
// 아두이노 헤더를 쓰지 않아 PC 에서 단위 테스트로 검증합니다.
// (firmware/test/test_vkp.cpp)
//
#include <stdint.h>
#include <stddef.h>

namespace vkbuf {

static const uint8_t  CAPACITY           = 16;     // 담아 둘 최대 개수
static const uint32_t REPLAY_MAX_AGE_MS  = 10000;  // 이 안쪽이면 되살립니다
static const uint8_t  REPLAY_FLAG        = 0x80;   // kind 최상위 비트

struct Item {
    uint8_t  button;
    uint8_t  kind;
    uint32_t stampMs;
};

/**
 * 가장 오래된 것부터 나오는 고리 버퍼입니다.
 * 가득 차면 가장 오래된 것을 밀어내고 그 수를 셉니다 — 새 입력이 더 중요합니다.
 */
class PressBuffer {
public:
    void clear() {
        head_ = tail_ = count_ = 0;
        overwritten_ = expired_ = 0;
    }

    bool empty() const { return count_ == 0; }
    uint8_t size() const { return count_; }
    bool full() const { return count_ == CAPACITY; }

    /** 밀려나서 버려진 개수 (버퍼가 가득 찼을 때) */
    uint16_t overwritten() const { return overwritten_; }
    /** 너무 오래돼서 되살리지 않은 개수 */
    uint16_t expired() const { return expired_; }
    /** 어떤 이유로든 사용자에게 전달되지 못한 총 개수 */
    uint16_t missed() const { return (uint16_t)(overwritten_ + expired_); }

    void push(uint8_t button, uint8_t kind, uint32_t nowMs) {
        if (count_ == CAPACITY) {
            head_ = (uint8_t)((head_ + 1) % CAPACITY);   // 가장 오래된 것을 버립니다
            count_--;
            if (overwritten_ < 0xFFFF) {
                overwritten_++;
            }
        }
        items_[tail_].button  = button;
        items_[tail_].kind    = kind;
        items_[tail_].stampMs = nowMs;
        tail_ = (uint8_t)((tail_ + 1) % CAPACITY);
        count_++;
    }

    /**
     * 되살릴 것이 있으면 하나 꺼내 줍니다.
     *
     * 너무 오래된 항목은 조용히 건너뛰면서 expired 로 셉니다. 따라서 이 함수가
     * false 를 돌려주면 "되살릴 것이 정말 없다" 는 뜻입니다.
     *
     * @param out   되살릴 이벤트. kind 에 REPLAY_FLAG 가 세워져 나갑니다.
     * @return      꺼냈으면 true
     */
    bool popReplayable(uint32_t nowMs, Item& out) {
        while (count_ > 0) {
            Item it = items_[head_];
            head_ = (uint8_t)((head_ + 1) % CAPACITY);
            count_--;

            uint32_t age = (uint32_t)(nowMs - it.stampMs);   // millis() 되감김에도 안전
            if (age > REPLAY_MAX_AGE_MS) {
                if (expired_ < 0xFFFF) {
                    expired_++;
                }
                continue;                                    // 오래됐습니다 — 세기만 합니다
            }
            out = it;
            out.kind = (uint8_t)(it.kind | REPLAY_FLAG);
            return true;
        }
        return false;
    }

private:
    Item     items_[CAPACITY] = {};
    uint8_t  head_ = 0;
    uint8_t  tail_ = 0;
    uint8_t  count_ = 0;
    uint16_t overwritten_ = 0;
    uint16_t expired_ = 0;
};

}  // namespace vkbuf
