/*
 * press_fsm.h — 버튼 하나의 "누름 해석" 상태머신
 *
 * 개발 초기에 이 판단은 전부 안드로이드 쪽(UsbSerialService의 3초 디바운스)에 있었습니다.
 * 손 떨림 방지라는 이 작품의 핵심 안전장치가 폰에 있으면, 폰이 바쁘거나 서비스가
 * 잠들었을 때 그대로 무너집니다. 그래서 기기로 내렸습니다.
 *
 * 해석 규칙
 *   - 접점 튐(chatter) 은 DEBOUNCE_MS 안의 변화를 무시해 걸러냅니다.
 *   - 누른 채로 LONG_MS 가 지나면 손을 떼기 전에 바로 LONG 을 냅니다(길게 누름 = AI 도우미).
 *     떼는 순간까지 기다리면 "언제 떼야 하지?"를 모르는 어르신에게는 반응이 없는 것처럼 느껴집니다.
 *   - 뗀 뒤 REPEAT_GUARD_MS 안에 같은 버튼이 또 눌리면 떨림으로 보고 버립니다.
 *
 * 두 번 누름(DOUBLE)은 기본으로 꺼 두었습니다. 켜면 "한 번 누름"을 확정하기 위해
 * 매번 DOUBLE_GAP_MS 만큼 기다려야 해서, 모든 실행이 0.35초씩 느려집니다.
 * 어르신 대상 기기에서는 기능 하나보다 즉각 반응이 더 중요하다고 판단했습니다.
 *
 * Arduino 헤더를 쓰지 않는 순수 C++ 이라 PC에서 그대로 테스트합니다.
 *   → firmware/test/test_vkp.cpp
 */
#ifndef PRESS_FSM_H
#define PRESS_FSM_H

#include <stdint.h>

#include "vkp_frame.h"   // K_SHORT / K_LONG / K_DOUBLE

namespace vkp {

struct FsmConfig {
    uint16_t debounceMs;
    uint16_t longMs;
    uint16_t repeatGuardMs;
    uint16_t doubleGapMs;
    bool     doubleEnabled;

    FsmConfig()
        : debounceMs(25),
          longMs(700),
          repeatGuardMs(1200),
          doubleGapMs(350),
          doubleEnabled(false) {}
};

/** 버튼 한 개당 하나씩 둡니다. */
class PressFsm {
public:
    explicit PressFsm(const FsmConfig& cfg = FsmConfig())
        : cfg_(cfg), pressed_(false), lastEdgeMs_(0), downMs_(0),
          longSent_(false), lastEmitMs_(0), pendingSingle_(false), pendingSinceMs_(0),
          armed_(false) {}

    /**
     * 핀 변화가 들어왔을 때 부릅니다.
     * @param down   지금 눌려 있으면 true (풀업이므로 LOW = 눌림)
     * @param nowMs  변화가 일어난 시각
     * @param kind   무언가 확정되면 여기에 담습니다 (K_SHORT/K_LONG/K_DOUBLE)
     * @return 확정된 누름이 있으면 true
     */
    bool onEdge(bool down, uint32_t nowMs, uint8_t& kind) {
        if (armed_ && (uint32_t)(nowMs - lastEdgeMs_) < cfg_.debounceMs) {
            return false;                       // 접점 튐 — 무시
        }
        armed_ = true;
        if (down == pressed_) {
            return false;                       // 같은 상태 반복
        }
        pressed_ = down;
        lastEdgeMs_ = nowMs;

        if (down) {
            downMs_ = nowMs;
            longSent_ = false;
            return false;                       // 누른 순간에는 아직 확정 안 함
        }

        // 뗐을 때
        if (longSent_) {
            return false;                       // 이미 LONG 으로 처리했음
        }
        return resolveShort(nowMs, kind);
    }

    /**
     * 주기적으로(예: 10ms마다) 불러 주세요.
     * 누르고 있는 동안의 LONG 확정과, 두 번 누름 대기 종료를 여기서 처리합니다.
     */
    bool tick(uint32_t nowMs, uint8_t& kind) {
        if (pressed_ && !longSent_ && (uint32_t)(nowMs - downMs_) >= cfg_.longMs) {
            longSent_ = true;
            pendingSingle_ = false;
            lastEmitMs_ = nowMs;
            kind = K_LONG;
            return true;
        }
        if (pendingSingle_ && (uint32_t)(nowMs - pendingSinceMs_) >= cfg_.doubleGapMs) {
            pendingSingle_ = false;
            lastEmitMs_ = nowMs;
            kind = K_SHORT;
            return true;
        }
        return false;
    }

    bool isPressed() const { return pressed_; }

private:
    bool resolveShort(uint32_t nowMs, uint8_t& kind) {
        if (cfg_.doubleEnabled) {
            if (pendingSingle_ && (uint32_t)(nowMs - pendingSinceMs_) < cfg_.doubleGapMs) {
                pendingSingle_ = false;
                lastEmitMs_ = nowMs;
                kind = K_DOUBLE;
                return true;                    // 두 번 누름 확정
            }
            pendingSingle_ = true;              // 한 번 누름은 조금 기다렸다 확정
            pendingSinceMs_ = nowMs;
            return false;
        }

        // 떨림 방지: 방금 실행한 버튼을 또 누른 것은 버립니다.
        if (lastEmitMs_ != 0 && (uint32_t)(nowMs - lastEmitMs_) < cfg_.repeatGuardMs) {
            return false;
        }
        lastEmitMs_ = nowMs;
        kind = K_SHORT;
        return true;
    }

    FsmConfig cfg_;
    bool     pressed_;
    uint32_t lastEdgeMs_;
    uint32_t downMs_;
    bool     longSent_;
    uint32_t lastEmitMs_;
    bool     pendingSingle_;
    uint32_t pendingSinceMs_;
    bool     armed_;        // 첫 변화는 디바운스를 적용하지 않기 위한 표시
};

}  // namespace vkp

#endif  // PRESS_FSM_H
