/*
 * power_policy.h — 언제, 얼마나 오래 잘지 정하는 규칙
 *
 * 이 기기는 폰에서 전원을 얻는 유선 액세서리다. 하루 종일 꽂아 두는 물건이므로
 * 아무 일도 안 하는 동안 폰 배터리를 먹으면 안 된다. 그런데 USB 기기가 잠드는 것은
 * 위험해서, "언제 자도 되는가"와 "얼마나 자도 되는가"를 따로 판단해야 한다.
 *
 * 왜 규칙을 따로 떼어 냈는가
 * ---------------------------------------------------------------------------
 * 처음에는 판단이 .ino 안에 섞여 있었고, 자는 길이가 0.5초로 고정이었다.
 * 고정 길이에는 두 가지 문제가 있다.
 *
 *   1. 오래 조용해도 계속 0.5초마다 깬다. 밤새 안 쓰는 동안에도 초당 두 번씩
 *      깨어나 DTR 을 확인한다 — 그 깨어남 자체가 전력이다.
 *   2. 반대로 길이를 늘리면 앱이 다시 붙은 것을 늦게 알아차린다.
 *
 * 그래서 **깰수록 길게 자도록** 했다. 처음엔 0.5초, 그다음 1초, 그다음부터 2초.
 * 방금 쓰다 만 상황에서는 촘촘히 확인하고, 한참 안 쓴 상황에서는 드물게 확인한다.
 * 사람이 기기를 다시 쓰는 순간은 버튼 눌림(GPIO 깨움)으로 즉시 잡히므로,
 * 길어진 슬라이스가 늦추는 것은 "앱이 혼자 다시 붙는 경우"뿐이다.
 *
 * 워치독과의 관계 — 이 규칙이 존재하는 진짜 이유
 * ---------------------------------------------------------------------------
 * 자는 동안에는 어느 태스크도 워치독을 먹이지 못한다. 슬라이스가 워치독 시간을
 * 넘으면 멀쩡한 기기가 "멈췄다"고 판정되어 재시작한다. 그래서 슬라이스 상한은
 * 반드시 워치독 시간보다 넉넉히 짧아야 하고, 그 계산을 사람의 주의력에 맡기지
 * 않으려고 여기서 강제한다. {@link clampSlice} 가 어떤 설정값을 받아도
 * 워치독의 절반을 넘지 않게 자른다.
 *
 * Arduino 헤더를 쓰지 않는 순수 C++ 이라 PC에서 그대로 테스트한다.
 *   → firmware/test/test_vkp.cpp
 */
#ifndef POWER_POLICY_H
#define POWER_POLICY_H

#include <stdint.h>

namespace vkpower {

struct PolicyConfig {
    /** 이만큼 조용해야 잠들기 시작한다. */
    uint32_t idleMs;
    /** 처음 잘 때의 길이. */
    uint32_t firstSliceUs;
    /** 아무리 오래 자도 이보다 길게는 자지 않는다. */
    uint32_t maxSliceUs;
    /** 태스크 워치독 시간. 슬라이스는 이것의 절반을 절대 넘지 않는다. */
    uint32_t wdtTimeoutMs;

    PolicyConfig()
        : idleMs(3000),
          firstSliceUs(500000),      // 0.5초
          maxSliceUs(2000000),       // 2초
          wdtTimeoutMs(5000) {}
};

struct Decision {
    /** 자도 되는가 */
    bool     sleep;
    /** 잔다면 얼마나 (마이크로초). sleep 이 false 면 0. */
    uint32_t sliceUs;
    /** 왜 안 자는지 (기록·시험용) */
    enum Reason {
        SLEEP_OK      = 0,
        BUSY_LINK     = 1,  // 폰이 듣고 있다
        BUSY_BUFFER   = 2,  // 아직 전할 것이 남았다
        BUSY_BUTTON   = 3,  // 버튼이 눌린 채다
        BUSY_TOO_SOON = 4   // 조용해진 지 얼마 안 됐다
    } reason;
};

/**
 * 슬라이스를 워치독이 물지 않는 범위로 자른다.
 *
 * 절반으로 잡은 이유: 깨어난 직후 워치독을 먹이기까지 태스크 스케줄링 지연이
 * 있고, 자러 들어가기 직전에도 이미 얼마간 시간이 흘러 있다. 절반이면
 * 그 양쪽을 합쳐도 충분히 여유가 있다.
 */
inline uint32_t clampSlice(uint32_t sliceUs, uint32_t wdtTimeoutMs) {
    uint32_t capUs = (wdtTimeoutMs / 2) * 1000u;
    if (capUs == 0) {
        return 0;
    }
    return sliceUs > capUs ? capUs : sliceUs;
}

/**
 * 이번에 자도 되는지, 잔다면 얼마나 잘지 정한다.
 *
 * @param linkUp            폰이 듣고 있는가 (USB CDC 의 DTR)
 * @param bufferEmpty       폰에 못 보낸 누름이 남아 있지 않은가
 * @param anyButtonDown     지금 눌려 있는 버튼이 있는가
 * @param msSinceActivity   마지막으로 뭔가 일어난 뒤 흐른 시간
 * @param consecutiveSleeps 깨어나서 아무 일 없이 다시 자기를 반복한 횟수
 */
inline Decision decide(const PolicyConfig& cfg,
                       bool linkUp,
                       bool bufferEmpty,
                       bool anyButtonDown,
                       uint32_t msSinceActivity,
                       uint16_t consecutiveSleeps) {
    Decision d;
    d.sleep = false;
    d.sliceUs = 0;

    // 폰이 듣고 있으면 자지 않는다. 자는 동안 들어온 바이트는 그냥 사라진다.
    if (linkUp) {
        d.reason = Decision::BUSY_LINK;
        return d;
    }
    // 아직 전하지 못한 누름이 있으면, 그것부터 보내야 한다.
    if (!bufferEmpty) {
        d.reason = Decision::BUSY_BUFFER;
        return d;
    }
    // 눌린 채로 자면 레벨 깨움이 즉시 걸려 자는 의미가 없다(오히려 전력을 더 쓴다).
    if (anyButtonDown) {
        d.reason = Decision::BUSY_BUTTON;
        return d;
    }
    if (msSinceActivity < cfg.idleMs) {
        d.reason = Decision::BUSY_TOO_SOON;
        return d;
    }

    // 깰수록 길게 잔다: 0.5초 → 1초 → 2초 → (상한).
    // 2의 거듭제곱으로 늘리되 자릿수가 넘치지 않게 지수를 먼저 묶는다.
    uint16_t steps = consecutiveSleeps > 16 ? 16 : consecutiveSleeps;
    uint64_t slice = (uint64_t)cfg.firstSliceUs << steps;
    if (slice > (uint64_t)cfg.maxSliceUs) {
        slice = cfg.maxSliceUs;
    }

    d.sleep = true;
    d.sliceUs = clampSlice((uint32_t)slice, cfg.wdtTimeoutMs);
    d.reason = Decision::SLEEP_OK;
    return d;
}

/**
 * 잔 시간의 비율을 천분율로 돌려 준다 (0~1000).
 *
 * 전류계 없이도 "절전이 실제로 돌고 있는가"를 숫자로 확인할 수 있는 값이다.
 * 전류를 추정하지는 않는다 — 잰 것은 어디까지나 <b>잠들어 있던 시간</b>이다.
 */
inline uint16_t dutyPermille(uint64_t sleptUs, uint64_t upUs) {
    if (upUs == 0) {
        return 0;
    }
    uint64_t permille = (sleptUs * 1000u) / upUs;
    return permille > 1000u ? 1000u : (uint16_t)permille;
}

}  // namespace vkpower

#endif  // POWER_POLICY_H
