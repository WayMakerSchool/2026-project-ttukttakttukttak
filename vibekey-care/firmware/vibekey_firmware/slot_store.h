/*
 * slot_store.h — 버튼 매핑을 기기에 담아 두기 (VKP v1 확장)
 *
 * 왜 기기가 매핑을 들고 있어야 하는가
 * ---------------------------------------------------------------------------
 * 지금까지 "1번 버튼 = 카카오톡" 이라는 정보는 폰 안에만 있었습니다. 그래서
 *   · 폰을 바꾸면       → 처음부터 다시 정해야 하고
 *   · 앱을 지웠다 깔면  → 마찬가지이며
 *   · 가족이 대신 잡아 드린 설정도 폰과 함께 사라집니다.
 *
 * 어르신께 이 설정을 다시 잡는 일은 기기를 처음 받는 일과 똑같은 부담입니다.
 * 그래서 앱이 매핑을 정하고 나면(사람이 직접 고르든, AI가 정하든) 그 결과를
 * 기기 NVS(플래시)에 같이 적어 둡니다. 새 폰에 앱을 깔면 기기가 먼저 알려 줍니다.
 * → 기기가 "내가 무엇을 하는 물건인지"를 스스로 알고 다닙니다.
 *
 * 조각내어 보내는 이유
 * ---------------------------------------------------------------------------
 * VKP v1 의 payload 한도는 16바이트입니다. 그런데 패키지명 하나가
 * "com.samsung.android.dialer" 처럼 26바이트를 넘기기도 합니다. 한도를 늘리면
 * 이미 맞춰 둔 앱 쪽 FrameCodec 과 규격이 어긋나므로, 한도는 그대로 두고
 * 값을 조각으로 나눠 보냅니다.
 *
 *   payload = slot(1) | index(1) | count(1) | data(0~13)
 *
 * 조각은 반드시 0번부터 차례대로 와야 합니다. 하나라도 건너뛰면 그 슬롯의
 * 조립을 통째로 버립니다. 절반만 저장된 매핑은 "눌러도 엉뚱한 앱이 열리는"
 * 고장이 되는데, 화면을 안 보는 사용자에게 그것이 가장 나쁜 결과이기 때문입니다.
 *
 * 이 헤더는 Arduino 헤더를 하나도 쓰지 않는 순수 C++ 입니다.
 * PC에서 그대로 컴파일해 단위 테스트합니다. → firmware/test/test_vkp.cpp
 * 안드로이드 쪽 SlotMapCodec.java 와 규격이 같습니다.
 */
#ifndef SLOT_STORE_H
#define SLOT_STORE_H

#include <stdint.h>
#include <stddef.h>
#include <string.h>

namespace vkmap {

// ---------------------------------------------------------------- 상수

static const uint8_t MAX_SLOTS   = 3;    // 기기의 버튼 수
static const uint8_t MAX_VALUE   = 96;   // 한 슬롯에 담을 수 있는 글자 수 (UTF-8 바이트)
static const uint8_t CHUNK_DATA  = 13;   // 조각 하나가 나르는 글자 수 (16 - 머리말 3)
static const uint8_t HEADER_LEN  = 3;    // slot, index, count
static const uint8_t MAX_CHUNKS  = 8;    // 13 × 8 = 104 ≥ 96

/** 한 슬롯에 저장하는 값. "패키지명\n보여 줄 이름" 형태로 씁니다. */
struct Entry {
    uint8_t len;
    char    text[MAX_VALUE];

    Entry() : len(0) { memset(text, 0, sizeof(text)); }

    bool empty() const { return len == 0; }

    void clear() {
        len = 0;
        memset(text, 0, sizeof(text));
    }

    /** 값을 통째로 넣습니다. 한도를 넘으면 넣지 않고 false. */
    bool set(const char* src, uint8_t n) {
        if (src == nullptr || n > MAX_VALUE) {
            return false;
        }
        clear();
        memcpy(text, src, n);
        len = n;
        return true;
    }
};

/** 조각을 받아들인 결과 */
enum Result {
    NEED_MORE = 0,  // 잘 받았고, 다음 조각을 기다립니다
    COMPLETE  = 1,  // 마지막 조각까지 다 받았습니다
    REJECTED  = 2   // 말이 안 되는 조각이라 버렸습니다 (그 슬롯의 조립도 함께 버림)
};

// ---------------------------------------------------------------- 보내는 쪽

/** 값 하나를 보내려면 조각이 몇 개 필요한지. 빈 값도 "비었다"고 알려야 하므로 1개입니다. */
inline uint8_t chunkCount(uint8_t valueLen) {
    if (valueLen == 0) {
        return 1;
    }
    uint8_t count = (uint8_t)((valueLen + CHUNK_DATA - 1) / CHUNK_DATA);
    return count > MAX_CHUNKS ? MAX_CHUNKS : count;
}

/**
 * index 번째 조각의 payload 를 만듭니다.
 * @param out 최소 16바이트
 * @return payload 길이. slot·index 가 범위를 벗어나면 0.
 */
inline uint8_t buildChunk(uint8_t slot, const Entry& entry, uint8_t index, uint8_t* out) {
    if (slot < 1 || slot > MAX_SLOTS || out == nullptr) {
        return 0;
    }
    uint8_t count = chunkCount(entry.len);
    if (index >= count) {
        return 0;
    }
    uint8_t offset = (uint8_t)(index * CHUNK_DATA);
    uint8_t take   = 0;
    if (offset < entry.len) {
        take = (uint8_t)(entry.len - offset);
        if (take > CHUNK_DATA) {
            take = CHUNK_DATA;
        }
    }
    out[0] = slot;
    out[1] = index;
    out[2] = count;
    for (uint8_t i = 0; i < take; i++) {
        out[HEADER_LEN + i] = (uint8_t)entry.text[offset + i];
    }
    return (uint8_t)(HEADER_LEN + take);
}

// ---------------------------------------------------------------- 받는 쪽

/**
 * 조각을 모아 값 하나로 되돌립니다.
 *
 * 슬롯마다 따로 모으기 때문에, 1번 슬롯을 보내는 도중에 2번 슬롯 조각이 끼어들어도
 * 서로를 망가뜨리지 않습니다. 다만 <b>한 슬롯 안에서는</b> 반드시 0번부터 차례대로여야
 * 합니다. 순서가 어긋나면 그 슬롯을 통째로 버리고 REJECTED 를 돌려 줍니다.
 */
class Assembler {
public:
    Assembler() {
        for (uint8_t i = 0; i < MAX_SLOTS; i++) {
            next_[i]  = 0;
            count_[i] = 0;
        }
    }

    /**
     * 조각 하나를 넣습니다.
     * @param slot  1~3
     * @param data  머리말을 뺀 글자들 (없으면 nullptr)
     * @param len   data 의 길이 (0~13)
     */
    Result accept(uint8_t slot, uint8_t index, uint8_t count,
                  const uint8_t* data, uint8_t len) {
        if (slot < 1 || slot > MAX_SLOTS) {
            return REJECTED;
        }
        uint8_t s = (uint8_t)(slot - 1);

        if (count < 1 || count > MAX_CHUNKS || index >= count || len > CHUNK_DATA) {
            reset(s);
            return REJECTED;
        }
        if (index == 0) {
            // 새로 시작합니다. 앞서 모으다 만 것이 있으면 여기서 지워집니다.
            partial_[s].clear();
            count_[s] = count;
            next_[s]  = 0;
        } else if (next_[s] != index || count_[s] != count) {
            // 조각을 건너뛰었거나, 도중에 전체 개수가 바뀌었습니다.
            reset(s);
            return REJECTED;
        }
        if ((uint16_t)partial_[s].len + len > MAX_VALUE) {
            reset(s);
            return REJECTED;
        }

        for (uint8_t i = 0; i < len; i++) {
            partial_[s].text[partial_[s].len + i] = (char)data[i];
        }
        partial_[s].len = (uint8_t)(partial_[s].len + len);
        next_[s] = (uint8_t)(index + 1);

        if (next_[s] >= count_[s]) {
            next_[s] = 0;
            return COMPLETE;
        }
        return NEED_MORE;
    }

    /** 다 모인 값. accept 가 COMPLETE 를 돌려준 직후에 읽습니다. */
    const Entry& completed(uint8_t slot) const {
        return partial_[slotIndex(slot)];
    }

    /** 모으다 만 것을 버립니다. */
    void reset(uint8_t slotIndexZeroBased) {
        if (slotIndexZeroBased >= MAX_SLOTS) {
            return;
        }
        partial_[slotIndexZeroBased].clear();
        next_[slotIndexZeroBased]  = 0;
        count_[slotIndexZeroBased] = 0;
    }

    void resetAll() {
        for (uint8_t i = 0; i < MAX_SLOTS; i++) {
            reset(i);
        }
    }

private:
    static uint8_t slotIndex(uint8_t slot) {
        if (slot < 1 || slot > MAX_SLOTS) {
            return 0;
        }
        return (uint8_t)(slot - 1);
    }

    Entry   partial_[MAX_SLOTS];
    uint8_t next_[MAX_SLOTS];    // 다음에 와야 하는 조각 번호
    uint8_t count_[MAX_SLOTS];   // 이번에 오기로 한 전체 조각 수
};

}  // namespace vkmap

#endif  // SLOT_STORE_H
