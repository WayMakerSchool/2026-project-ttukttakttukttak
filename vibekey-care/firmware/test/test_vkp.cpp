/*
 * test_vkp.cpp — 펌웨어 단위 테스트 (PC에서 그대로 돌아갑니다)
 *
 * "임베디드 소프트웨어는 기기가 있어야 검증할 수 있다"는 말은 절반만 맞습니다.
 * 프레임 조립·CRC·재동기화·누름 판정처럼 하드웨어에 닿지 않는 로직은 PC에서
 * 수만 번 돌려 볼 수 있고, 그래야 기기에서는 전기적인 문제만 남습니다.
 *
 * 실행:  ./run_tests.sh
 */
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

#include "../vibekey_firmware/vkp_frame.h"
#include "../vibekey_firmware/press_fsm.h"
#include "../vibekey_firmware/press_buffer.h"
#include "../vibekey_firmware/slot_store.h"
#include "../vibekey_firmware/pocket_guard.h"
#include "../vibekey_firmware/power_policy.h"

using namespace vkp;

static int g_pass = 0;
static int g_fail = 0;

static void check(bool ok, const char* what) {
    if (ok) {
        g_pass++;
    } else {
        g_fail++;
        std::printf("  [실패] %s\n", what);
    }
}

// ---------------------------------------------------------------- 난수

/*
 * 손상 실험은 "언제 어디서 돌려도 같은 숫자가 나와야" 근거가 됩니다.
 * 표준 라이브러리의 rand() 는 구현마다 수열이 달라(리눅스 glibc 와 윈도우 MSVC 가
 * 서로 다릅니다) 다른 컴퓨터에서 돌리면 다른 값이 나옵니다.
 * 그래서 난수 수열을 직접 들고 갑니다 — xorshift32, Marsaglia(2003).
 * 씨앗이 같으면 어떤 컴파일러에서도 같은 수열이고, 따라서 같은 결과가 나옵니다.
 */
static uint32_t g_rngState = 1;

static void rng_seed(uint32_t s) {
    g_rngState = s ? s : 1;
}

static uint32_t rng_next() {
    uint32_t x = g_rngState;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    g_rngState = x;
    return x;
}

static uint32_t rng_below(uint32_t n) {
    return rng_next() % n;
}

// ---------------------------------------------------------------- CRC

static void test_crc16_known_vectors() {
    // CRC-16/CCITT-FALSE 의 널리 알려진 검증값
    const uint8_t check_input[9] = {'1','2','3','4','5','6','7','8','9'};
    check(crc16(check_input, 9) == 0x29B1, "CRC16('123456789') == 0x29B1");

    // 한 비트만 달라져도 값이 달라져야 합니다.
    const uint8_t a[3] = {0x01, 0x02, 0x03};
    const uint8_t b[3] = {0x01, 0x02, 0x02};
    check(crc16(a, 3) != crc16(b, 3), "한 비트가 다르면 CRC도 다르다");
}

// ---------------------------------------------------------------- 왕복

static void test_roundtrip() {
    uint8_t payload[4] = {2, K_SHORT, 0x34, 0x12};
    uint8_t buf[MAX_FRAME];
    uint8_t n = encode(77, T_EVT_PRESS, payload, 4, buf);
    check(n == 11, "4바이트 payload 프레임 길이는 11 (머리 4 + 본문 4 + CRC 2 + 끝 1)");

    Decoder d;
    Frame f;
    bool got = false;
    for (uint8_t i = 0; i < n; i++) {
        got = d.push(buf[i], f);
    }
    check(got, "왕복: 프레임이 완성된다");
    check(f.seq == 77 && f.type == T_EVT_PRESS && f.len == 4, "왕복: 머리말이 그대로다");
    check(f.u8(0) == 2 && f.u8(1) == K_SHORT, "왕복: 버튼·종류가 그대로다");
    check(f.u16(2) == 0x1234, "왕복: 리틀엔디언 2바이트를 제대로 읽는다");
    check(d.crcErrors == 0 && d.discarded == 0, "왕복: 버린 바이트가 없다");
}

static void test_zero_length_payload() {
    uint8_t buf[MAX_FRAME];
    uint8_t n = encode(1, T_PING, nullptr, 0, buf);
    check(n == 7, "빈 payload 프레임은 7바이트");

    Decoder d;
    Frame f;
    bool got = false;
    for (uint8_t i = 0; i < n; i++) {
        got = d.push(buf[i], f);
    }
    check(got && f.type == T_PING && f.len == 0, "빈 payload도 해석된다");
}

static void test_payload_too_long_is_refused() {
    uint8_t big[MAX_PAYLOAD + 1] = {0};
    uint8_t buf[64];
    check(encode(1, T_STATS, big, MAX_PAYLOAD + 1, buf) == 0,
          "payload가 한도를 넘으면 만들지 않는다");
}

// ---------------------------------------------------------------- 잡음

/**
 * 이 작품의 핵심 주장은 "오작동 = 요금 사고"입니다. 그래서 가장 중요한 검사는
 * "깨진 프레임이 절대 실행으로 이어지지 않는가" 입니다.
 * 모든 비트를 하나씩 뒤집어 보며(= 전수 검사) 단 한 번도 통과하지 않는지 봅니다.
 */
static void test_every_single_bit_flip_is_rejected() {
    uint8_t payload[4] = {1, K_SHORT, 0x10, 0x00};
    uint8_t original[MAX_FRAME];
    uint8_t n = encode(42, T_EVT_PRESS, payload, 4, original);

    int accepted = 0;
    int trials = 0;
    for (uint8_t byteIdx = 0; byteIdx < n; byteIdx++) {
        for (uint8_t bit = 0; bit < 8; bit++) {
            uint8_t corrupted[MAX_FRAME];
            std::memcpy(corrupted, original, n);
            corrupted[byteIdx] ^= (uint8_t)(1 << bit);
            trials++;

            Decoder d;
            Frame f;
            for (uint8_t i = 0; i < n; i++) {
                if (d.push(corrupted[i], f)) {
                    // 통과했다면, 적어도 원본과 똑같은 내용이어서는 안 됩니다.
                    if (f.seq == 42 && f.type == T_EVT_PRESS && f.len == 4 &&
                        f.u8(0) == 1 && f.u8(1) == K_SHORT) {
                        accepted++;
                    }
                }
            }
        }
    }
    std::printf("  · 비트 뒤집기 %d회 중 원본으로 오인된 것: %d회\n", trials, accepted);
    check(accepted == 0, "비트 하나가 뒤집힌 프레임은 하나도 실행되지 않는다");
}

// ---------------------------------------------------------------- CRC 세기 실험

/** 처음 설계였던 CRC-8/ATM(0x07). 지금은 비교 실험 전용으로만 남겨 둡니다. */
static uint8_t crc8_legacy(const uint8_t* data, size_t len) {
    uint8_t crc = 0x00;
    for (size_t i = 0; i < len; i++) {
        crc ^= data[i];
        for (uint8_t b = 0; b < 8; b++) {
            crc = (crc & 0x80) ? (uint8_t)((crc << 1) ^ 0x07) : (uint8_t)(crc << 1);
        }
    }
    return crc;
}

/** CRC8을 쓰던 시절의 프레임(6+LEN)을 그대로 재현합니다. */
static uint8_t encode_crc8(uint8_t seq, uint8_t type,
                           const uint8_t* payload, uint8_t len, uint8_t* out) {
    uint8_t n = 0;
    out[n++] = STX;
    out[n++] = seq;
    out[n++] = type;
    out[n++] = len;
    for (uint8_t i = 0; i < len; i++) {
        out[n++] = payload[i];
    }
    out[n++] = crc8_legacy(out + 1, (size_t)(3 + len));
    out[n++] = ETX;
    return n;
}

/** CRC8 프레임을 검사만 해 보는 최소 디코더 (재동기화 없이 앞머리부터 그대로 확인) */
static bool accepts_crc8(const uint8_t* buf, uint8_t n, uint8_t& button, uint8_t& type) {
    if (n < 6 || buf[0] != STX) {
        return false;
    }
    uint8_t len = buf[3];
    if (len > MAX_PAYLOAD || (uint8_t)(len + 6) != n) {
        return false;
    }
    if (buf[n - 2] != crc8_legacy(buf + 1, (size_t)(3 + len)) || buf[n - 1] != ETX) {
        return false;
    }
    type = buf[2];
    button = len > 0 ? buf[4] : 0;
    return true;
}

/** CRC16 프레임을 재동기화 없이 앞머리부터만 확인 — CRC8과 똑같은 조건으로 견주기 위한 것 */
static bool accepts_crc16_flat(const uint8_t* buf, uint8_t n,
                               uint8_t& button, uint8_t& type) {
    if (n < OVERHEAD || buf[0] != STX) {
        return false;
    }
    uint8_t len = buf[3];
    if (len > MAX_PAYLOAD || (uint8_t)(len + OVERHEAD) != n) {
        return false;
    }
    uint16_t want = (uint16_t)(buf[n - 3] | ((uint16_t)buf[n - 2] << 8));
    if (crc16(buf + 1, (size_t)(3 + len)) != want || buf[n - 1] != ETX) {
        return false;
    }
    type = buf[2];
    button = len > 0 ? buf[4] : 0;
    return true;
}

/**
 * 무작위 잡음 대량 주입 — 이 작품에서 가장 중요한 숫자를 뽑는 시험입니다.
 *
 * 버튼 한 번이 택시 요금으로 이어지므로, 물어야 할 질문은 "잡음을 걸러내는가"가
 * 아니라 "깨진 프레임이 실행으로 새어 나가는 비율이 얼마인가" 입니다.
 * 온전한 프레임을 손상시켜 200만 번씩 흘려보내고, 똑같은 손상을 CRC8(초기 설계)와
 * CRC16(현재 설계)에 동시에 먹여 비교합니다. 씨앗을 고정해 두어 항상 같은 숫자가 나옵니다.
 *
 * @param minFlips,maxFlips 뒤집을 비트 수의 범위
 * @param wholeFrame        true면 CRC·끝바이트까지 포함해 프레임 전체를 손상시킵니다
 */
static void run_corruption_experiment(const char* title,
                                      int minFlips, int maxFlips, bool wholeFrame,
                                      int& leak8Out, int& leak16FlatOut,
                                      int& leak16Out) {
    // 10만 건으로는 CRC16 쪽 유출이 한 자리라 잡음과 구분되지 않습니다.
    // 200만 건이면 두 설계의 비율이 안정적으로 읽히고, 실행에 10초 남짓 걸립니다.
    const int TRIALS = 2000000;
    rng_seed(20260903);
    int leak8 = 0;
    int leak16flat = 0;
    int leak16 = 0;

    for (int t = 0; t < TRIALS; t++) {
        uint8_t seq     = (uint8_t)(1 + rng_below(255));
        uint8_t button  = (uint8_t)(1 + rng_below(3));
        uint8_t payload[4] = {button, K_SHORT, (uint8_t)(rng_next() & 0xFF), 0x00};

        uint8_t f16[MAX_FRAME];
        uint8_t n16 = encode(seq, T_EVT_PRESS, payload, 4, f16);
        uint8_t f8[MAX_FRAME];
        uint8_t n8 = encode_crc8(seq, T_EVT_PRESS, payload, 4, f8);

        // 손상 범위: 두 설계의 프레임 길이가 다르므로(11 vs 10) 공통 구간만 건드려
        // 완전히 같은 조건에서 비교합니다.
        uint8_t span = wholeFrame ? (uint8_t)(n8 < n16 ? n8 : n16) : 8;

        // 같은 비트를 두 번 뒤집으면 원래대로 돌아가 손상이 아니게 되므로 겹치지 않게 고릅니다.
        int flips = minFlips + (int)rng_below((uint32_t)(maxFlips - minFlips + 1));
        uint8_t used[16][2];
        int done = 0;
        while (done < flips) {
            uint8_t pos = (uint8_t)rng_below(span);
            uint8_t bitIdx = (uint8_t)rng_below(8);
            bool dup = false;
            for (int k = 0; k < done; k++) {
                if (used[k][0] == pos && used[k][1] == bitIdx) {
                    dup = true;
                }
            }
            if (dup) {
                continue;
            }
            used[done][0] = pos;
            used[done][1] = bitIdx;
            done++;
            uint8_t bit = (uint8_t)(1 << bitIdx);
            f16[pos] ^= bit;
            f8[pos]  ^= bit;
        }

        // CRC16 (현재 설계) — 재동기화까지 포함한 진짜 디코더로
        Decoder d;
        Frame f;
        for (uint8_t i = 0; i < n16; i++) {
            if (d.push(f16[i], f)) {
                if (f.type == T_EVT_PRESS && f.len == 4 && f.u8(0) >= 1 && f.u8(0) <= 3) {
                    leak16++;
                }
            }
        }

        // CRC8 (초기 설계)
        uint8_t b8 = 0, t8 = 0;
        if (accepts_crc8(f8, n8, b8, t8) && t8 == T_EVT_PRESS && b8 >= 1 && b8 <= 3) {
            leak8++;
        }

        // CRC16을 CRC8과 똑같은 조건(재동기화 없음)에서도 재 둡니다.
        // 위의 leak16 은 실제로 기기에 올라가는 디코더라 재동기화까지 하므로,
        // 손상된 바이트 안에서 프레임을 찾아낼 기회가 CRC8 쪽보다 오히려 많습니다.
        // 두 값을 함께 두어야 "검사값을 한 바이트 늘린 효과"와
        // "디코더가 더 부지런한 효과"를 섞지 않고 읽을 수 있습니다.
        uint8_t b16 = 0, t16 = 0;
        if (accepts_crc16_flat(f16, n16, b16, t16) &&
            t16 == T_EVT_PRESS && b16 >= 1 && b16 <= 3) {
            leak16flat++;
        }
    }

    std::printf("  · %s (%d건)\n", title, TRIALS);
    std::printf("      CRC8  (재동기화 없음) : %6d건  (%.4f%%)\n",
                leak8, 100.0 * leak8 / TRIALS);
    std::printf("      CRC16 (같은 조건)     : %6d건  (%.4f%%)\n",
                leak16flat, 100.0 * leak16flat / TRIALS);
    std::printf("      CRC16 (실제 디코더)   : %6d건  (%.4f%%)\n",
                leak16, 100.0 * leak16 / TRIALS);
    leak8Out = leak8;
    leak16FlatOut = leak16flat;
    leak16Out = leak16;
}

/**
 * 실험 A — 전선을 타고 들어오는 흔한 잡음 수준(머리·본문에 1~3비트).
 * 이 범위에서는 CRC8도 전부 걸러 냅니다. 즉 "CRC8은 부실하다"는 말은 사실이 아닙니다.
 */
static void test_light_corruption_both_safe() {
    int leak8 = 0, leak16flat = 0, leak16 = 0;
    run_corruption_experiment("1~3비트 손상 → 버튼 실행으로 새어 나간 수",
                              1, 3, false, leak8, leak16flat, leak16);
    check(leak8 == 0,      "1~3비트 손상은 CRC8도 모두 걸러 낸다");
    check(leak16flat == 0, "1~3비트 손상은 CRC16도 모두 걸러 낸다");
    check(leak16 == 0,     "1~3비트 손상은 실제 디코더로 재도 0건이다");
}

/**
 * 실험 B — 커넥터가 흔들리거나 전원이 튈 때처럼 여러 비트가 한꺼번에 망가지는 경우.
 * 여기서 두 설계가 갈립니다. CRC8은 8비트라 대략 256분의 1이 우연히 들어맞고,
 * CRC16은 65,536분의 1이라 사실상 0입니다. 프레임당 1바이트를 더 쓰는 대신
 * "요금이 발생하는 오실행"의 확률을 두 자릿수 낮추는 거래라서 올릴 값어치가 있습니다.
 */
static void test_heavy_corruption_crc16_is_stronger() {
    int leak8 = 0, leak16flat = 0, leak16 = 0;
    run_corruption_experiment("4~10비트 손상(프레임 전체) → 버튼 실행으로 새어 나간 수",
                              4, 10, true, leak8, leak16flat, leak16);
    // 검사값이 우연히 들어맞는 일은 확률이지 0이 아닙니다. 그래서 "0건"이 아니라
    // "얼마나 드문가"를 단언합니다. 같은 조건(재동기화 없음)에서 CRC16은 CRC8보다
    // 검사값이 한 바이트 길어 우연 일치가 256분의 1로 줄어듭니다.
    check(leak8 > 0, "이 손상 범위에서는 CRC8이 실제로 실행을 새어 보낸다");
    check(leak16flat * 5 <= leak8,
          "같은 조건에서 CRC16은 CRC8보다 최소 5배 적게 샌다");
    check(leak16 < leak8,
          "재동기화까지 하는 실제 디코더로 재도 CRC16이 CRC8보다 적게 샌다");
}

static void test_resync_after_garbage() {
    uint8_t payload[2] = {3, K_SHORT};
    uint8_t good[MAX_FRAME];
    uint8_t n = encode(9, T_EVT_PRESS, payload, 2, good);

    // 앞에 잡음을 잔뜩 붙입니다. 가짜 STX(0xAA)와 옛 평문("True\n")도 섞습니다.
    std::vector<uint8_t> stream;
    const char* legacy = "True\n";
    for (const char* p = legacy; *p; p++) {
        stream.push_back((uint8_t)*p);
    }
    stream.push_back(0xAA);
    stream.push_back(0xFF);
    stream.push_back(0x00);
    stream.push_back(0xAA);
    for (uint8_t i = 0; i < n; i++) {
        stream.push_back(good[i]);
    }

    Decoder d;
    Frame f;
    int frames = 0;
    for (size_t i = 0; i < stream.size(); i++) {
        if (d.push(stream[i], f)) {
            frames++;
        }
    }
    check(frames == 1, "잡음 뒤에 온 온전한 프레임 하나를 되찾는다");
    check(f.seq == 9 && f.u8(0) == 3, "되찾은 프레임의 내용이 맞다");
    check(d.discarded > 0, "버린 바이트를 세어 둔다 (프레임 오류율 계산용)");
}

static void test_two_frames_back_to_back() {
    uint8_t p1[1] = {1};
    uint8_t p2[1] = {2};
    uint8_t a[MAX_FRAME], b[MAX_FRAME];
    uint8_t na = encode(1, T_EVT_PRESS, p1, 1, a);
    uint8_t nb = encode(2, T_EVT_PRESS, p2, 1, b);

    Decoder d;
    Frame f;
    std::vector<uint8_t> seq;
    for (uint8_t i = 0; i < na; i++) seq.push_back(a[i]);
    for (uint8_t i = 0; i < nb; i++) seq.push_back(b[i]);

    int frames = 0;
    uint8_t lastButton = 0;
    for (size_t i = 0; i < seq.size(); i++) {
        if (d.push(seq[i], f)) {
            frames++;
            lastButton = f.u8(0);
        }
    }
    check(frames == 2, "연달아 붙은 두 프레임을 둘 다 읽는다");
    check(lastButton == 2, "두 번째 프레임 내용이 맞다");
}

static void test_truncated_frame_then_valid() {
    uint8_t p[1] = {1};
    uint8_t buf[MAX_FRAME];
    uint8_t n = encode(5, T_EVT_PRESS, p, 1, buf);

    Decoder d;
    Frame f;
    int frames = 0;
    // 케이블을 꽂는 순간처럼 프레임이 중간에서 잘려 들어온 경우
    for (uint8_t i = 0; i < n - 2; i++) {
        if (d.push(buf[i], f)) frames++;
    }
    check(frames == 0, "잘린 프레임은 실행되지 않는다");
    for (uint8_t i = 0; i < n; i++) {
        if (d.push(buf[i], f)) frames++;
    }
    check(frames == 1, "그 뒤에 온 온전한 프레임은 정상 처리된다");
}

// ---------------------------------------------------------------- 누름 판정

static void test_debounce_ignores_chatter() {
    FsmConfig cfg;
    PressFsm fsm(cfg);
    uint8_t kind;

    check(!fsm.onEdge(true, 1000, kind), "누른 순간에는 아직 확정하지 않는다");
    // 접점 튐: 1005ms에 떨어졌다 1010ms에 다시 붙음 → 둘 다 무시돼야 함
    check(!fsm.onEdge(false, 1005, kind), "디바운스 안의 변화는 무시한다");
    check(!fsm.onEdge(true, 1010, kind), "디바운스 안의 변화는 무시한다(2)");
    check(fsm.onEdge(false, 1200, kind), "제대로 떼면 한 번 확정된다");
    check(kind == K_SHORT, "짧게 누름으로 판정한다");
}

static void test_long_press_fires_while_held() {
    FsmConfig cfg;
    PressFsm fsm(cfg);
    uint8_t kind = 0xFF;

    fsm.onEdge(true, 0, kind);
    check(!fsm.tick(500, kind), "0.5초로는 길게 누름이 아니다");
    check(fsm.tick(700, kind), "0.7초를 넘기면 손을 떼기 전에 확정된다");
    check(kind == K_LONG, "길게 누름으로 판정한다");
    check(!fsm.onEdge(false, 1500, kind), "이미 확정했으니 뗄 때 또 내지 않는다");
}

static void test_tremor_guard_blocks_repeat() {
    FsmConfig cfg;
    PressFsm fsm(cfg);
    uint8_t kind;

    fsm.onEdge(true, 0, kind);
    check(fsm.onEdge(false, 100, kind), "첫 누름은 실행된다");
    fsm.onEdge(true, 300, kind);
    check(!fsm.onEdge(false, 400, kind), "떨림으로 곧바로 또 눌린 것은 버린다");
    fsm.onEdge(true, 2000, kind);
    check(fsm.onEdge(false, 2100, kind), "가드 시간이 지난 뒤에는 다시 실행된다");
}

static void test_double_tap_when_enabled() {
    FsmConfig cfg;
    cfg.doubleEnabled = true;
    PressFsm fsm(cfg);
    uint8_t kind = 0xFF;

    fsm.onEdge(true, 0, kind);
    check(!fsm.onEdge(false, 80, kind), "두 번 누름을 켜면 한 번 누름은 잠시 보류된다");
    fsm.onEdge(true, 200, kind);
    check(fsm.onEdge(false, 260, kind), "간격 안에 또 누르면 확정된다");
    check(kind == K_DOUBLE, "두 번 누름으로 판정한다");
}

static void test_single_tap_resolves_after_gap() {
    FsmConfig cfg;
    cfg.doubleEnabled = true;
    PressFsm fsm(cfg);
    uint8_t kind = 0xFF;

    fsm.onEdge(true, 0, kind);
    fsm.onEdge(false, 80, kind);
    check(!fsm.tick(200, kind), "간격 안에는 아직 기다린다");
    check(fsm.tick(500, kind), "간격이 지나면 한 번 누름으로 확정된다");
    check(kind == K_SHORT, "한 번 누름으로 판정한다");
}

// ---------------------------------------------------------------- 실행


// ---------------------------------------------------------------- 오프라인 버퍼

static void test_buffer_replays_recent_presses() {
    vkbuf::PressBuffer b;
    b.clear();
    b.push(1, 0, 1000);
    b.push(2, 1, 1200);

    vkbuf::Item it;
    check(b.popReplayable(1500, it), "폰이 돌아오면 방금 누른 것은 되살아난다");
    check(it.button == 1, "누른 순서를 지킨다 (먼저 누른 1번이 먼저)");
    check((it.kind & vkbuf::REPLAY_FLAG) != 0, "되살린 것은 표시가 붙는다");
    check((it.kind & 0x7F) == 0, "표시를 떼면 원래 누름 방식이 남는다");

    check(b.popReplayable(1500, it), "두 번째도 되살아난다");
    check(it.button == 2, "두 번째는 2번 버튼");
    check(!b.popReplayable(1500, it), "더 없으면 false");
    check(b.missed() == 0, "정상적으로 다 되살렸으면 놓친 것은 없다");
}

static void test_buffer_drops_stale_presses_instead_of_firing_them() {
    vkbuf::PressBuffer b;
    b.clear();
    b.push(1, 0, 1000);                       // 10분 전에 누른 "전화"
    vkbuf::Item it;
    // 나이 제한을 넘겼습니다. 지금 와서 전화를 걸면 고장보다 나쁩니다.
    check(!b.popReplayable(1000 + vkbuf::REPLAY_MAX_AGE_MS + 1, it),
          "너무 오래된 누름은 되살리지 않는다");
    check(b.expired() == 1, "대신 놓친 입력으로 센다");
    check(b.missed() == 1, "사용자에게 전달되지 못한 수에 포함된다");
}

static void test_buffer_keeps_newest_when_full() {
    vkbuf::PressBuffer b;
    b.clear();
    for (int i = 0; i < vkbuf::CAPACITY + 3; i++) {
        b.push((uint8_t)(i % 3 + 1), 0, (uint32_t)(1000 + i));
    }
    check(b.size() == vkbuf::CAPACITY, "용량을 넘지 않는다");
    check(b.overwritten() == 3, "밀려난 3개를 센다");

    vkbuf::Item it;
    check(b.popReplayable(1100, it), "가장 오래된 것부터 나온다");
    // 0,1,2 가 밀려났으므로 남은 것 중 가장 오래된 것은 i=3
    check(it.button == (uint8_t)(3 % 3 + 1), "밀려난 뒤 남은 가장 오래된 항목이 먼저");
}

static void test_buffer_survives_millis_wraparound() {
    vkbuf::PressBuffer b;
    b.clear();
    // millis() 는 약 49.7일마다 0으로 돌아갑니다. 그 순간에도 나이 계산이 맞아야 합니다.
    uint32_t before = 0xFFFFF000u;
    b.push(1, 0, before);
    uint32_t after = before + 500;            // 되감겨서 작은 수가 됩니다
    vkbuf::Item it;
    check(b.popReplayable(after, it), "되감김 직후에도 나이를 올바로 계산한다");
    check(it.button == 1, "값이 온전하다");
}

// ---------------------------------------------------------------- 재시작 후 SEQ

static void test_seq_resync_after_restart_has_no_duplicate_execution() {
    // 상황: 기기가 프레임을 보냈는데 ACK 를 받기 전에 워치독으로 재시작했습니다.
    //       재시작하면 SEQ 가 0부터 다시 시작합니다.
    //       폰은 "마지막에 실행한 SEQ" 하나만 기억하므로, 재시작 전 SEQ 와
    //       우연히 같으면 새 누름을 중복으로 오인해 무시할 수 있습니다.
    //       그래서 기기는 재시작 직후 HELLO 를 먼저 보내고, 폰은 HELLO 를 받으면
    //       중복 판정 상태를 지웁니다. 이 테스트는 그 계약을 고정합니다.
    Decoder dec;
    Frame f;
    uint8_t buf[64];

    // 재시작 전: SEQ 7 로 1번 버튼
    uint8_t n = encode(7, T_EVT_PRESS, (const uint8_t*)"\x01\x00\x10\x00", 4, buf);
    bool got = false;
    for (uint8_t i = 0; i < n; i++) { if (dec.push(buf[i], f)) got = true; }
    check(got && f.seq == 7, "재시작 전 프레임을 받았다");

    // 재시작: HELLO 가 먼저 온다
    uint8_t hello[6] = {1, 3, 0, 3, 0, 6};   // 재시작 원인 6 = 태스크 워치독
    n = encode(0, T_HELLO, hello, sizeof(hello), buf);
    got = false;
    for (uint8_t i = 0; i < n; i++) { if (dec.push(buf[i], f)) got = true; }
    check(got && f.type == T_HELLO, "재시작 직후 HELLO 가 먼저 온다");
    check(f.u8(5) == 6, "HELLO 가 재시작 원인을 싣고 온다 (워치독)");

    // 재시작 후: SEQ 가 0 부터 다시 시작한다
    n = encode(0, T_EVT_PRESS, (const uint8_t*)"\x02\x00\x20\x00", 4, buf);
    got = false;
    for (uint8_t i = 0; i < n; i++) { if (dec.push(buf[i], f)) got = true; }
    check(got && f.seq == 0, "재시작 후 SEQ 는 0 부터 다시 시작한다");
    check(f.u8(0) == 2, "새 누름이 온전히 전달된다");
    check(dec.crcErrors == 0 && dec.framingErrors == 0,
          "재시작 경계에서 프레임이 깨지지 않는다");
}


// ---------------------------------------------------------------- 버튼 매핑 저장

/*
 * 매핑을 기기에 담아 두는 기능(slot_store.h)의 검사입니다.
 *
 * 여기서 가장 나쁜 결과는 "절반만 저장된 매핑"입니다. 화면을 안 보고 누르는
 * 기기이므로, 1번 버튼에 엉뚱한 앱이 붙어 있어도 사용자는 누르기 전까지 모릅니다.
 * 그래서 조각이 하나라도 어긋나면 통째로 버리는지를 특히 꼼꼼히 봅니다.
 */

static vkmap::Entry makeEntry(const char* text) {
    vkmap::Entry e;
    e.set(text, (uint8_t)std::strlen(text));
    return e;
}

/** 조각내어 보낸 뒤 그대로 다시 모읍니다. */
static bool roundTripMap(uint8_t slot, const char* text, vkmap::Entry& out) {
    vkmap::Entry src = makeEntry(text);
    vkmap::Assembler asm_;
    uint8_t total = vkmap::chunkCount(src.len);
    for (uint8_t i = 0; i < total; i++) {
        uint8_t p[MAX_PAYLOAD];
        uint8_t n = vkmap::buildChunk(slot, src, i, p);
        if (n == 0) {
            return false;
        }
        vkmap::Result r = asm_.accept(p[0], p[1], p[2],
                                      p + vkmap::HEADER_LEN,
                                      (uint8_t)(n - vkmap::HEADER_LEN));
        if (i + 1 < total && r != vkmap::NEED_MORE) {
            return false;
        }
        if (i + 1 == total && r != vkmap::COMPLETE) {
            return false;
        }
    }
    out = asm_.completed(slot);
    return true;
}

static void test_map_roundtrip_long_package_name() {
    // 한 조각(13바이트)에 안 들어가는 실제 패키지명입니다.
    const char* value = "com.samsung.android.dialer\n전화";
    vkmap::Entry got;
    check(roundTripMap(1, value, got), "매핑 조각내기/모으기가 끝까지 간다");
    check(got.len == (uint8_t)std::strlen(value), "모은 길이가 보낸 길이와 같다");
    check(std::memcmp(got.text, value, got.len) == 0, "모은 내용이 보낸 내용과 같다");
}

static void test_map_roundtrip_short_and_empty() {
    vkmap::Entry got;
    check(roundTripMap(2, "a", got) && got.len == 1, "한 글자짜리도 오간다");

    // 빈 값도 "이 버튼은 비었다"는 뜻이라 반드시 전달돼야 합니다.
    vkmap::Entry empty;
    check(vkmap::chunkCount(0) == 1, "빈 값도 조각 하나로 보낸다");
    uint8_t p[MAX_PAYLOAD];
    uint8_t n = vkmap::buildChunk(3, empty, 0, p);
    check(n == vkmap::HEADER_LEN, "빈 값의 조각에는 글자가 없다");
    vkmap::Assembler asm_;
    check(asm_.accept(p[0], p[1], p[2], nullptr, 0) == vkmap::COMPLETE,
          "빈 값도 한 번에 완성된다");
    check(asm_.completed(3).empty(), "완성된 값이 비어 있다");
}

static void test_map_fills_maximum_length() {
    char big[vkmap::MAX_VALUE + 1];
    for (uint8_t i = 0; i < vkmap::MAX_VALUE; i++) {
        big[i] = (char)('a' + (i % 26));
    }
    big[vkmap::MAX_VALUE] = '\0';

    vkmap::Entry got;
    check(roundTripMap(1, big, got), "한도(96바이트)까지 꽉 채워도 오간다");
    check(got.len == vkmap::MAX_VALUE, "한도까지 모두 모였다");
    check(vkmap::chunkCount(vkmap::MAX_VALUE) <= vkmap::MAX_CHUNKS,
          "한도를 채워도 조각 수가 규격 안에 있다");
}

static void test_map_rejects_skipped_chunk() {
    // 0번 다음에 2번이 왔습니다. 1번이 통째로 사라진 상황입니다.
    vkmap::Assembler asm_;
    uint8_t data[vkmap::CHUNK_DATA];
    std::memset(data, 'x', sizeof(data));

    check(asm_.accept(1, 0, 3, data, vkmap::CHUNK_DATA) == vkmap::NEED_MORE,
          "첫 조각은 받아들인다");
    check(asm_.accept(1, 2, 3, data, vkmap::CHUNK_DATA) == vkmap::REJECTED,
          "조각을 건너뛰면 버린다");
    // 버린 뒤에는 0번부터 다시 시작해야 합니다.
    check(asm_.accept(1, 1, 3, data, vkmap::CHUNK_DATA) == vkmap::REJECTED,
          "버린 다음에는 이어 붙이지 않는다");
    check(asm_.accept(1, 0, 1, data, 1) == vkmap::COMPLETE,
          "0번부터 다시 시작하면 정상 동작한다");
}

static void test_map_rejects_bad_header() {
    vkmap::Assembler asm_;
    uint8_t data[4] = {'a', 'b', 'c', 'd'};

    check(asm_.accept(0, 0, 1, data, 4) == vkmap::REJECTED, "0번 버튼은 없다");
    check(asm_.accept(9, 0, 1, data, 4) == vkmap::REJECTED, "9번 버튼은 없다");
    check(asm_.accept(1, 0, 0, data, 4) == vkmap::REJECTED, "조각 수 0은 말이 안 된다");
    check(asm_.accept(1, 0, (uint8_t)(vkmap::MAX_CHUNKS + 1), data, 4) == vkmap::REJECTED,
          "조각 수가 한도를 넘으면 버린다");
    check(asm_.accept(1, 3, 3, data, 4) == vkmap::REJECTED, "조각 번호가 전체 수 이상이면 버린다");
    check(asm_.accept(1, 0, 1, data, (uint8_t)(vkmap::CHUNK_DATA + 1)) == vkmap::REJECTED,
          "조각 하나가 너무 길면 버린다");
}

static void test_map_rejects_changed_count_midway() {
    // 보내는 도중에 "전체 3개"가 "전체 2개"로 바뀌었습니다. 다른 전송이 섞인 것입니다.
    vkmap::Assembler asm_;
    uint8_t data[vkmap::CHUNK_DATA];
    std::memset(data, 'y', sizeof(data));

    check(asm_.accept(2, 0, 3, data, vkmap::CHUNK_DATA) == vkmap::NEED_MORE,
          "첫 조각을 받는다");
    check(asm_.accept(2, 1, 2, data, vkmap::CHUNK_DATA) == vkmap::REJECTED,
          "도중에 전체 개수가 바뀌면 버린다");
}

static void test_map_slots_do_not_interfere() {
    // 1번 슬롯을 보내는 도중에 2번 슬롯 조각이 끼어들어도 서로 망가지면 안 됩니다.
    vkmap::Assembler asm_;
    uint8_t a[vkmap::CHUNK_DATA];
    uint8_t b[vkmap::CHUNK_DATA];
    std::memset(a, 'A', sizeof(a));
    std::memset(b, 'B', sizeof(b));

    check(asm_.accept(1, 0, 2, a, vkmap::CHUNK_DATA) == vkmap::NEED_MORE, "1번 첫 조각");
    check(asm_.accept(2, 0, 1, b, 5) == vkmap::COMPLETE, "2번은 그 사이에 끝난다");
    check(asm_.completed(2).len == 5, "2번 값이 온전하다");
    check(asm_.accept(1, 1, 2, a, 3) == vkmap::COMPLETE, "1번도 이어서 끝난다");
    check(asm_.completed(1).len == vkmap::CHUNK_DATA + 3, "1번 값이 온전하다");
    check(asm_.completed(1).text[0] == 'A', "1번에 2번 내용이 섞이지 않았다");
}

static void test_map_frame_fits_protocol_limit() {
    // 어떤 조각이든 VKP v1 의 payload 한도(16)를 넘지 않아야 합니다.
    check(vkmap::HEADER_LEN + vkmap::CHUNK_DATA <= MAX_PAYLOAD,
          "조각 하나가 payload 한도 안에 들어간다");

    char big[vkmap::MAX_VALUE + 1];
    std::memset(big, 'z', vkmap::MAX_VALUE);
    big[vkmap::MAX_VALUE] = '\0';
    vkmap::Entry entry = makeEntry(big);

    for (uint8_t i = 0; i < vkmap::chunkCount(entry.len); i++) {
        uint8_t p[MAX_PAYLOAD];
        uint8_t n = vkmap::buildChunk(1, entry, i, p);
        check(n > 0 && n <= MAX_PAYLOAD, "모든 조각이 한도 안에 들어간다");

        // 실제로 프레임으로 감쌌을 때도 규격 안에 들어가는지 확인합니다.
        uint8_t frame[MAX_FRAME];
        check(encode(0, T_SET_MAP, p, n, frame) == (uint8_t)(n + OVERHEAD),
              "조각을 프레임으로 감쌀 수 있다");
    }
}

static void test_map_survives_corrupted_chunk_being_dropped() {
    // 조각 하나가 CRC에서 걸려 버려졌다고 가정합니다(디코더가 아예 안 넘겨 줌).
    // 남은 조각만으로 절반짜리 값이 저장되면 안 됩니다.
    vkmap::Entry src = makeEntry("com.kakao.talk\n카카오톡");
    vkmap::Assembler asm_;
    uint8_t total = vkmap::chunkCount(src.len);
    check(total >= 3, "이 값은 조각이 셋 이상이다");

    bool sawComplete = false;
    for (uint8_t i = 0; i < total; i++) {
        if (i == 1) {
            continue;               // 두 번째 조각이 통째로 사라졌습니다
        }
        uint8_t p[MAX_PAYLOAD];
        uint8_t n = vkmap::buildChunk(1, src, i, p);
        if (asm_.accept(p[0], p[1], p[2], p + vkmap::HEADER_LEN,
                        (uint8_t)(n - vkmap::HEADER_LEN)) == vkmap::COMPLETE) {
            sawComplete = true;
        }
    }
    check(!sawComplete, "조각이 빠지면 절대 완성되지 않는다");
}


// ---------------------------------------------------------------- 주머니 오작동 차단

/*
 * 이 기기에서 가장 나쁜 두 가지 고장 중 하나가 "안 눌렀는데 실행되는 것"입니다.
 * 사용자는 화면을 보지 않으므로 실행된 사실 자체를 모릅니다. 그런데 이 기기는
 * 목에 걸거나 주머니에 넣고 다니는 물건이라, 옷감에 눌리는 일은 반드시 생깁니다.
 *
 * 아래 검사들은 "손가락처럼 보이는 것은 통과시키고, 옷감처럼 보이는 것은 막는가"를
 * 봅니다. 막는 쪽만 잘해도 안 되고 — 진짜 누름을 막으면 기기가 고장 난 것과 같습니다.
 */

static void test_guard_allows_a_normal_press() {
    vkguard::PocketGuard g;
    g.onDown(1, 1000);
    check(g.judge(1, 1200) == vkguard::ALLOW, "평범한 한 번 누름은 통과한다");
    g.onUp(1, 1300);

    // 한참 뒤에 다시 누르는 것도 당연히 통과해야 합니다.
    g.onDown(2, 9000);
    check(g.judge(2, 9150) == vkguard::ALLOW, "시간을 두고 누른 것도 통과한다");
}

static void test_guard_blocks_two_buttons_at_once() {
    // 손가락 하나로 두 개를 동시에 누를 수는 없습니다. 옷감은 누릅니다.
    vkguard::PocketGuard g;
    g.onDown(1, 1000);
    g.onDown(2, 1010);
    check(g.judge(1, 1100) == vkguard::BLOCK_MULTI, "두 개가 같이 눌리면 막는다");
}

static void test_guard_blocks_the_survivor_of_a_double_press() {
    // 두 개가 같이 눌렸다가 하나를 먼저 뗐습니다. 남은 하나가 "혼자 눌린 것"처럼
    // 보이지만, 시작이 동시 눌림이었으므로 통과시키면 안 됩니다.
    vkguard::PocketGuard g;
    g.onDown(1, 1000);
    g.onDown(2, 1010);
    g.onUp(2, 1100);
    check(g.judge(1, 1200) == vkguard::BLOCK_MULTI, "동시 눌림에서 살아남은 것도 막는다");
}

static void test_guard_blocks_a_stuck_button() {
    // 8초 넘게 눌린 채입니다. 사람이 이렇게 오래 누르고 있지 않습니다.
    vkguard::PocketGuard g;
    g.onDown(3, 1000);
    check(g.judge(3, 1000 + 8000) == vkguard::BLOCK_STUCK, "오래 눌려 있으면 막는다");
}

static void test_guard_allows_a_long_press_for_ai() {
    // "길게 누름"(0.7초)은 AI 도우미를 부르는 정상 동작입니다. 막으면 안 됩니다.
    vkguard::PocketGuard g;
    g.onDown(1, 1000);
    check(g.judge(1, 1700) == vkguard::ALLOW, "AI 도우미용 길게 누름은 통과한다");
    check(g.judge(1, 4000) == vkguard::ALLOW, "3초를 눌러도 아직은 사람으로 본다");
}

static void test_guard_blocks_rubbing_burst() {
    // 짧은 시간에 여러 번 몰립니다 — 주머니에서 비벼진 모양입니다.
    vkguard::PocketGuard g;
    uint32_t t = 1000;
    int allowed = 0;
    for (int i = 0; i < 8; i++) {
        g.onDown(1, t);
        if (g.judge(1, t) == vkguard::ALLOW) {
            allowed++;
        }
        g.onUp(1, t + 50);
        t += 300;                  // 0.3초 간격으로 계속
    }
    check(allowed < 8, "연달아 눌리면 어느 시점부터 막는다");
    check(allowed >= 3, "처음 몇 번은 사람일 수도 있으니 통과시킨다");
    check(g.blockedTotal() > 0, "막은 횟수를 세어 둔다");
}

static void test_guard_cools_down_then_recovers() {
    // 한 번 주머니로 판정하면 잠시 쉬고, 조용해지면 스스로 풀려야 합니다.
    vkguard::PocketGuard g;
    g.onDown(1, 1000);
    g.onDown(2, 1005);
    check(g.judge(1, 1100) == vkguard::BLOCK_MULTI, "동시 눌림으로 쉬는 시간에 들어간다");
    g.onUp(1, 1200);
    g.onUp(2, 1200);

    check(g.judge(1, 1300) == vkguard::BLOCK_COOLDOWN, "쉬는 동안에는 계속 막는다");

    // 충분히 조용해진 뒤에는 다시 받아 줘야 합니다.
    g.onDown(1, 20000);
    check(g.judge(1, 20100) == vkguard::ALLOW, "조용해지면 스스로 풀린다");
}

static void test_guard_counts_each_reason_separately() {
    vkguard::PocketGuard g;
    g.onDown(1, 1000);
    g.onDown(2, 1005);
    g.judge(1, 1100);                       // MULTI
    g.onUp(1, 1200);
    g.onUp(2, 1200);

    g.onDown(3, 30000);
    g.judge(3, 30000 + 9000);               // STUCK
    g.onUp(3, 40000);

    check(g.blockedMulti() == 1, "동시 눌림을 따로 센다");
    check(g.blockedStuck() == 1, "오래 눌림을 따로 센다");
    check(g.blockedTotal() >= 2, "합계가 맞는다");
}

static void test_guard_survives_millis_wraparound() {
    // millis() 는 약 49일마다 0으로 돌아갑니다. 그때 모든 누름이 막히거나
    // 반대로 모든 판정이 풀려 버리면 안 됩니다.
    const uint32_t nearMax = 0xFFFFFF00u;
    vkguard::PocketGuard g;

    g.onDown(1, nearMax);
    check(g.judge(1, (uint32_t)(nearMax + 200)) == vkguard::ALLOW,
          "자릿수가 넘어가는 순간에도 정상 누름은 통과한다");
    g.onUp(1, (uint32_t)(nearMax + 300));

    g.onDown(2, (uint32_t)(nearMax + 400));
    check(g.judge(2, (uint32_t)(nearMax + 400 + 9000)) == vkguard::BLOCK_STUCK,
          "자릿수가 넘어가도 오래 눌림은 막는다");
}

static void test_guard_ignores_unknown_button_numbers() {
    vkguard::PocketGuard g;
    g.onDown(0, 1000);         // 없는 버튼
    g.onDown(9, 1000);
    g.onUp(0, 1100);
    g.onUp(9, 1100);
    g.onDown(1, 1200);
    check(g.judge(1, 1300) == vkguard::ALLOW, "없는 버튼 번호는 조용히 무시한다");
}


// ---------------------------------------------------------------- 절전 규칙

/*
 * 이 기기는 폰에서 전원을 얻는다. 하루 종일 꽂아 두는 물건이라 아무 일도 안 하는
 * 동안 폰 배터리를 먹으면 안 된다. 그런데 잘못 자면 두 가지 사고가 난다.
 *
 *   · 폰이 듣고 있는데 자면  → 들어오는 바이트가 그냥 사라진다
 *   · 워치독보다 길게 자면   → 멀쩡한 기기가 "멈췄다"고 판정돼 재시작한다
 *
 * 두 번째가 특히 고약하다. 상수 하나만 잘못 고쳐도 기기가 몇 초마다 재시작하는데,
 * 겉으로는 "가끔 안 눌린다"로만 보인다. 그래서 규칙이 스스로 상한을 강제하는지 본다.
 */

static void test_power_never_sleeps_while_phone_listens() {
    vkpower::PolicyConfig cfg;
    vkpower::Decision d = vkpower::decide(cfg, /*linkUp=*/true, true, false, 60000, 0);
    check(!d.sleep, "폰이 듣고 있으면 자지 않는다");
    check(d.reason == vkpower::Decision::BUSY_LINK, "안 자는 이유를 남긴다");
}

static void test_power_never_sleeps_with_unsent_presses() {
    vkpower::PolicyConfig cfg;
    vkpower::Decision d = vkpower::decide(cfg, false, /*bufferEmpty=*/false, false, 60000, 0);
    check(!d.sleep, "전하지 못한 누름이 남아 있으면 자지 않는다");
    check(d.reason == vkpower::Decision::BUSY_BUFFER, "이유가 버퍼임을 남긴다");
}

static void test_power_never_sleeps_while_a_button_is_held() {
    // 눌린 채로 자면 레벨 깨움이 즉시 걸려, 자는 게 아니라 깨기를 반복한다.
    vkpower::PolicyConfig cfg;
    vkpower::Decision d = vkpower::decide(cfg, false, true, /*anyButtonDown=*/true, 60000, 0);
    check(!d.sleep, "버튼이 눌린 채면 자지 않는다");
    check(d.reason == vkpower::Decision::BUSY_BUTTON, "이유가 버튼임을 남긴다");
}

static void test_power_waits_until_quiet_enough() {
    vkpower::PolicyConfig cfg;
    check(!vkpower::decide(cfg, false, true, false, cfg.idleMs - 1, 0).sleep,
          "조용해진 지 얼마 안 됐으면 아직 자지 않는다");
    check(vkpower::decide(cfg, false, true, false, cfg.idleMs, 0).sleep,
          "충분히 조용해지면 잔다");
}

static void test_power_sleeps_longer_the_longer_it_stays_quiet() {
    // 밤새 안 쓰는 동안에도 0.5초마다 깨면 그 깨어남 자체가 전력이다.
    vkpower::PolicyConfig cfg;
    uint32_t s0 = vkpower::decide(cfg, false, true, false, 60000, 0).sliceUs;
    uint32_t s1 = vkpower::decide(cfg, false, true, false, 60000, 1).sliceUs;
    uint32_t s2 = vkpower::decide(cfg, false, true, false, 60000, 2).sliceUs;
    uint32_t s9 = vkpower::decide(cfg, false, true, false, 60000, 9).sliceUs;

    check(s0 == 500000, "처음엔 0.5초 잔다");
    check(s1 == 1000000, "다음엔 1초");
    check(s2 == 2000000, "그다음엔 2초");
    check(s9 == s2, "상한에 닿으면 더 늘지 않는다");
}

static void test_power_slice_never_outlasts_the_watchdog() {
    // 이 검사가 이 파일에서 가장 중요하다. 누가 상한을 크게 고쳐도
    // 규칙이 워치독의 절반으로 잘라야 한다 — 안 그러면 기기가 무한 재시작한다.
    vkpower::PolicyConfig cfg;
    cfg.firstSliceUs = 30000000;      // 30초 — 일부러 말이 안 되는 값
    cfg.maxSliceUs   = 30000000;
    cfg.wdtTimeoutMs = 5000;

    vkpower::Decision d = vkpower::decide(cfg, false, true, false, 60000, 5);
    check(d.sleep, "그래도 자기는 잔다");
    check(d.sliceUs == 2500000, "워치독 5초의 절반인 2.5초로 잘린다");
    check(d.sliceUs * 1000ull < (uint64_t)cfg.wdtTimeoutMs * 1000000ull,
          "어떤 설정에서도 슬라이스가 워치독을 넘지 않는다");

    // 워치독을 더 짧게 잡아도 같은 규칙이 적용돼야 한다.
    cfg.wdtTimeoutMs = 1000;
    check(vkpower::decide(cfg, false, true, false, 60000, 5).sliceUs == 500000,
          "워치독 1초면 0.5초로 잘린다");
}

static void test_power_clamp_handles_degenerate_config() {
    check(vkpower::clampSlice(1000000, 0) == 0, "워치독이 0이면 자지 않는 값으로 만든다");
    check(vkpower::clampSlice(100, 5000) == 100, "상한보다 짧으면 그대로 둔다");
}

static void test_power_duty_is_a_measured_ratio_not_a_guess() {
    // 전류를 추정하지 않는다. 잰 것은 "잠들어 있던 시간"의 비율뿐이다.
    check(vkpower::dutyPermille(0, 1000000) == 0, "한 번도 안 잤으면 0");
    check(vkpower::dutyPermille(500000, 1000000) == 500, "절반을 잤으면 500‰");
    check(vkpower::dutyPermille(1000000, 1000000) == 1000, "내내 잤으면 1000‰");
    check(vkpower::dutyPermille(0, 0) == 0, "켜진 시간이 0이어도 터지지 않는다");
    check(vkpower::dutyPermille(2000000, 1000000) == 1000, "1000‰ 를 넘지 않는다");

    // 오래 켜져 있어도(49일 넘게) 자릿수가 넘치지 않아야 한다.
    uint64_t sevenDaysUs = 7ull * 24 * 3600 * 1000000ull;
    check(vkpower::dutyPermille(sevenDaysUs / 2, sevenDaysUs) == 500,
          "일주일치 시간에도 비율이 정확하다");
}

int main() {
    std::printf("바이브키 펌웨어 단위 테스트\n");
    std::printf("---------------------------------------------\n");

    test_crc16_known_vectors();
    test_roundtrip();
    test_zero_length_payload();
    test_payload_too_long_is_refused();
    test_every_single_bit_flip_is_rejected();
    test_light_corruption_both_safe();
    test_heavy_corruption_crc16_is_stronger();
    test_resync_after_garbage();
    test_two_frames_back_to_back();
    test_truncated_frame_then_valid();
    test_debounce_ignores_chatter();
    test_long_press_fires_while_held();
    test_tremor_guard_blocks_repeat();
    test_double_tap_when_enabled();
    test_single_tap_resolves_after_gap();
    test_buffer_replays_recent_presses();
    test_buffer_drops_stale_presses_instead_of_firing_them();
    test_buffer_keeps_newest_when_full();
    test_buffer_survives_millis_wraparound();
    test_seq_resync_after_restart_has_no_duplicate_execution();

    test_map_roundtrip_long_package_name();
    test_map_roundtrip_short_and_empty();
    test_map_fills_maximum_length();
    test_map_rejects_skipped_chunk();
    test_map_rejects_bad_header();
    test_map_rejects_changed_count_midway();
    test_map_slots_do_not_interfere();
    test_map_frame_fits_protocol_limit();
    test_map_survives_corrupted_chunk_being_dropped();

    test_guard_allows_a_normal_press();
    test_guard_blocks_two_buttons_at_once();
    test_guard_blocks_the_survivor_of_a_double_press();
    test_guard_blocks_a_stuck_button();
    test_guard_allows_a_long_press_for_ai();
    test_guard_blocks_rubbing_burst();
    test_guard_cools_down_then_recovers();
    test_guard_counts_each_reason_separately();
    test_guard_survives_millis_wraparound();
    test_guard_ignores_unknown_button_numbers();

    test_power_never_sleeps_while_phone_listens();
    test_power_never_sleeps_with_unsent_presses();
    test_power_never_sleeps_while_a_button_is_held();
    test_power_waits_until_quiet_enough();
    test_power_sleeps_longer_the_longer_it_stays_quiet();
    test_power_slice_never_outlasts_the_watchdog();
    test_power_clamp_handles_degenerate_config();
    test_power_duty_is_a_measured_ratio_not_a_guess();

    std::printf("---------------------------------------------\n");
    std::printf("통과 %d개 / 실패 %d개\n", g_pass, g_fail);
    return g_fail == 0 ? 0 : 1;
}
