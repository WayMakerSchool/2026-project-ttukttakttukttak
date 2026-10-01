/*
 * pocket_guard.h — 주머니·가방 안에서 눌린 것을 기기가 스스로 걸러 냅니다
 *
 * 왜 필요한가
 * ---------------------------------------------------------------------------
 * 이 기기의 가장 나쁜 고장은 두 가지입니다.
 *   (1) 눌렀는데 아무 일도 안 일어난다   → 오프라인 버퍼·재전송으로 막았습니다
 *   (2) 안 눌렀는데 뭔가 실행된다        → 여기서 막습니다
 *
 * (2)가 더 나쁩니다. 사용자는 화면을 보지 않으므로 실행된 사실 자체를 모릅니다.
 * 택시가 불려 있거나, 통화가 걸려 있거나, 데이터가 소모되고 있어도 알아채지
 * 못합니다. 그런데 이 기기는 목에 걸거나 주머니에 넣고 다니는 물건입니다.
 * 옷감에 눌리는 일은 "있을 수 있는 일"이 아니라 "반드시 생기는 일"입니다.
 *
 * 부품을 늘리지 않고 어떻게 구분하는가
 * ---------------------------------------------------------------------------
 * 정전용량 터치를 붙이면 손가락인지 옷감인지 바로 알 수 있지만 기판을 다시 떠야
 * 합니다. 대신 <b>사람이 누르는 방식과 옷감이 누르는 방식이 다르다</b>는 점을 씁니다.
 *
 *   손가락                                   주머니 속 옷감
 *   ─────────────────────────────────────    ─────────────────────────────────
 *   버튼 하나를 누른다                        여러 개가 한꺼번에 눌린다
 *   눌렀다 곧 뗀다 (보통 0.1~1초)             한참 눌린 채로 있는다
 *   한 번 누르고 결과를 기다린다               비비면서 여러 번 연달아 눌린다
 *
 * 그래서 세 가지를 봅니다.
 *   1. 동시 눌림  — 두 개 이상이 같이 눌려 있으면 손가락이 아닙니다.
 *   2. 오래 눌림  — STUCK_MS(8초)를 넘겨 눌려 있으면 눌린 채 갇힌 것입니다.
 *   3. 연달아 눌림 — 짧은 시간에 여러 번 몰리면 비벼진 것입니다.
 *
 * 한 번 주머니로 판정하면 잠시(COOLDOWN) 모든 입력을 무시합니다. 옷감은 한 번
 * 스치면 그 뒤로도 계속 스치기 때문입니다. 조용해지면 스스로 풀립니다.
 *
 * 왜 폰이 아니라 기기가 판단하는가
 * ---------------------------------------------------------------------------
 * 걸러 내지 못한 신호는 이미 프레임이 되어 나갔고, 폰은 그것을 "정상 누름"으로
 * 받습니다. 폰에서 막으려면 폰이 깨어 있어야 하는데, 주머니에 들어 있는 동안이
 * 바로 폰이 잠들어 있는 시간입니다. 판단은 신호가 생기는 곳에서 해야 합니다.
 *
 * Arduino 헤더를 쓰지 않는 순수 C++ 이라 PC에서 그대로 테스트합니다.
 *   → firmware/test/test_vkp.cpp
 */
#ifndef POCKET_GUARD_H
#define POCKET_GUARD_H

#include <stdint.h>

namespace vkguard {

static const uint8_t MAX_BUTTONS  = 3;
static const uint8_t BURST_MEMORY = 8;   // 최근 누름 시각을 몇 개까지 기억할지

struct GuardConfig {
    /** 이만큼 계속 눌려 있으면 "눌린 채 갇혔다"고 봅니다. */
    uint16_t stuckMs;
    /** 이 시간 안에 */
    uint16_t burstWindowMs;
    /** 이 횟수 이상 몰리면 비벼진 것으로 봅니다. */
    uint8_t  burstCount;
    /** 주머니로 판정한 뒤 이만큼 모든 입력을 무시합니다. */
    uint16_t cooldownMs;

    GuardConfig()
        : stuckMs(8000),
          burstWindowMs(3000),
          burstCount(5),
          cooldownMs(1500) {}
};

/** 판정 결과 */
enum Verdict {
    ALLOW          = 0,  // 사람이 누른 것으로 보입니다 — 실행합니다
    BLOCK_MULTI    = 1,  // 두 개 이상이 같이 눌렸습니다
    BLOCK_STUCK    = 2,  // 너무 오래 눌려 있었습니다
    BLOCK_BURST    = 3,  // 짧은 시간에 너무 여러 번 눌렸습니다
    BLOCK_COOLDOWN = 4   // 방금 주머니로 판정해 잠시 쉬는 중입니다
};

class PocketGuard {
public:
    explicit PocketGuard(const GuardConfig& cfg = GuardConfig())
        : cfg_(cfg), downMask_(0), multiFlag_(false),
          cooldownUntil_(0), inCooldown_(false),
          recentHead_(0), recentCount_(0),
          blockedMulti_(0), blockedStuck_(0), blockedBurst_(0), blockedCooldown_(0) {
        for (uint8_t i = 0; i < MAX_BUTTONS; i++) {
            downAt_[i] = 0;
        }
        for (uint8_t i = 0; i < BURST_MEMORY; i++) {
            recent_[i] = 0;
        }
    }

    /** 버튼이 눌리기 시작했습니다. (button 은 1~3) */
    void onDown(uint8_t button, uint32_t nowMs) {
        uint8_t i = index(button);
        if (i >= MAX_BUTTONS) {
            return;
        }
        downMask_ = (uint8_t)(downMask_ | (uint8_t)(1u << i));
        downAt_[i] = nowMs;
        if (countDown() >= 2) {
            // 두 개가 같이 눌린 순간을 기억해 둡니다. 하나를 먼저 떼더라도
            // 남은 하나가 "혼자 눌린 것"으로 통과하면 안 되기 때문입니다.
            multiFlag_ = true;
        }
    }

    /** 버튼에서 손을 뗐습니다. */
    void onUp(uint8_t button, uint32_t nowMs) {
        (void)nowMs;
        uint8_t i = index(button);
        if (i >= MAX_BUTTONS) {
            return;
        }
        downMask_ = (uint8_t)(downMask_ & (uint8_t)~(1u << i));
        if (downMask_ == 0) {
            multiFlag_ = false;   // 전부 뗐으니 다시 깨끗한 상태입니다
        }
    }

    /**
     * 누름이 확정됐을 때 "이걸 실행해도 됩니까?" 하고 묻습니다.
     * ALLOW 가 아니면 그 누름은 폰으로 내보내지 않습니다.
     */
    Verdict judge(uint8_t button, uint32_t nowMs) {
        if (inCooldown_) {
            if ((uint32_t)(nowMs - cooldownUntil_) < 0x80000000u) {
                inCooldown_ = false;      // 조용해졌습니다. 다시 정상으로 돌아갑니다.
            } else {
                blockedCooldown_++;
                startCooldown(nowMs);     // 계속 스치는 중이면 쉬는 시간을 늘립니다
                return BLOCK_COOLDOWN;
            }
        }

        if (multiFlag_ || countDown() >= 2) {
            blockedMulti_++;
            startCooldown(nowMs);
            return BLOCK_MULTI;
        }

        uint8_t i = index(button);
        if (i < MAX_BUTTONS && isDown(i)) {
            uint32_t held = (uint32_t)(nowMs - downAt_[i]);
            if (held >= cfg_.stuckMs) {
                blockedStuck_++;
                startCooldown(nowMs);
                return BLOCK_STUCK;
            }
        }

        if (recentWithin(nowMs) + 1 >= cfg_.burstCount) {
            blockedBurst_++;
            startCooldown(nowMs);
            return BLOCK_BURST;
        }

        remember(nowMs);
        return ALLOW;
    }

    /** 주머니로 보고 버린 전체 횟수 (앱의 자가진단 화면에 보여 줍니다) */
    uint16_t blockedTotal() const {
        uint32_t sum = (uint32_t)blockedMulti_ + blockedStuck_ + blockedBurst_ + blockedCooldown_;
        return (uint16_t)(sum > 0xFFFF ? 0xFFFF : sum);
    }

    uint16_t blockedMulti() const { return blockedMulti_; }
    uint16_t blockedStuck() const { return blockedStuck_; }
    uint16_t blockedBurst() const { return blockedBurst_; }
    uint16_t blockedCooldown() const { return blockedCooldown_; }

    /** 지금 쉬는 중인지 (시험·진단용) */
    bool cooling() const { return inCooldown_; }

private:
    static uint8_t index(uint8_t button) {
        return (button >= 1 && button <= MAX_BUTTONS) ? (uint8_t)(button - 1) : MAX_BUTTONS;
    }

    bool isDown(uint8_t i) const {
        return (downMask_ & (uint8_t)(1u << i)) != 0;
    }

    uint8_t countDown() const {
        uint8_t n = 0;
        for (uint8_t i = 0; i < MAX_BUTTONS; i++) {
            if (isDown(i)) {
                n++;
            }
        }
        return n;
    }

    void startCooldown(uint32_t nowMs) {
        cooldownUntil_ = nowMs + cfg_.cooldownMs;
        inCooldown_ = true;
    }

    /** 최근 창 안에 들어온 누름이 몇 번이었는지 */
    uint8_t recentWithin(uint32_t nowMs) const {
        uint8_t n = 0;
        for (uint8_t k = 0; k < recentCount_; k++) {
            if ((uint32_t)(nowMs - recent_[k]) <= cfg_.burstWindowMs) {
                n++;
            }
        }
        return n;
    }

    void remember(uint32_t nowMs) {
        recent_[recentHead_] = nowMs;
        recentHead_ = (uint8_t)((recentHead_ + 1) % BURST_MEMORY);
        if (recentCount_ < BURST_MEMORY) {
            recentCount_++;
        }
    }

    GuardConfig cfg_;
    uint8_t     downMask_;
    uint32_t    downAt_[MAX_BUTTONS];
    bool        multiFlag_;

    uint32_t    cooldownUntil_;
    bool        inCooldown_;

    uint32_t    recent_[BURST_MEMORY];
    uint8_t     recentHead_;
    uint8_t     recentCount_;

    uint16_t    blockedMulti_;
    uint16_t    blockedStuck_;
    uint16_t    blockedBurst_;
    uint16_t    blockedCooldown_;
};

}  // namespace vkguard

#endif  // POCKET_GUARD_H
