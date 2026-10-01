package com.example.vibekey;

import java.io.UnsupportedEncodingException;

/**
 * 버튼 매핑을 기기(ESP32-S3)에 담아 두기 위한 코덱입니다.
 *
 * <p><b>왜 기기가 들고 있어야 하나</b><br>
 * "1번 버튼 = 카카오톡" 은 지금까지 폰에만 있었습니다. 그래서 폰을 바꾸거나 앱을
 * 다시 깔면 어르신이 처음부터 다시 정하셔야 했습니다. 그 부담은 기기를 새로 받는
 * 것과 다르지 않습니다. 이제 매핑이 정해지면(직접 고르시든, AI가 정하든) 기기의
 * 플래시에도 같이 적어 두고, 새 폰에서는 기기가 먼저 알려 줍니다.
 *
 * <p><b>조각내어 보내는 이유</b><br>
 * VKP v1 의 payload 한도는 16바이트인데 패키지명 하나가
 * {@code com.samsung.android.dialer} 처럼 26바이트를 넘습니다. 한도를 늘리면 이미
 * 맞춰 둔 펌웨어와 규격이 어긋나므로, 한도는 그대로 두고 값을 조각으로 나눕니다.
 *
 * <pre>
 *   payload = slot(1) | index(1) | count(1) | data(0~13)
 *   값      = "패키지명\n보여 줄 이름"  (UTF-8, 최대 96바이트)
 * </pre>
 *
 * <p>조각은 반드시 0번부터 차례대로 와야 합니다. 하나라도 어긋나면 그 슬롯을 통째로
 * 버립니다. 절반만 저장된 매핑은 "눌러도 엉뚱한 앱이 열리는" 고장이 되는데,
 * 화면을 안 보고 누르는 사용자에게 그것이 가장 나쁜 결과이기 때문입니다.
 *
 * <p>펌웨어의 {@code firmware/vibekey_firmware/slot_store.h} 와 규격이 같습니다.
 * 안드로이드 클래스를 쓰지 않는 순수 자바라 단위 테스트로 바로 검증할 수 있습니다.
 * (app/src/test/java/com/example/vibekey/SlotMapCodecTest.java)
 */
public final class SlotMapCodec {

    public static final int MAX_SLOTS  = Prefs.SLOT_COUNT;
    public static final int MAX_VALUE  = 96;   // 한 슬롯의 값 (UTF-8 바이트)
    public static final int CHUNK_DATA = 13;   // 조각 하나가 나르는 바이트 (16 - 머리말 3)
    public static final int HEADER_LEN = 3;    // slot, index, count
    public static final int MAX_CHUNKS = 8;    // 13 × 8 = 104 ≥ 96

    /** 조각을 받아들인 결과 */
    public static final int NEED_MORE = 0;
    public static final int COMPLETE  = 1;
    public static final int REJECTED  = 2;

    private static final String UTF8 = "UTF-8";

    private SlotMapCodec() {
    }

    // ---------------------------------------------------------------- 값 만들기

    /** 기기에서 되찾아 온 매핑 하나. */
    public static final class Entry {
        public final String packageName;
        public final String label;

        public Entry(String packageName, String label) {
            this.packageName = packageName == null ? "" : packageName;
            this.label = label == null ? "" : label;
        }

        public boolean isEmpty() {
            return packageName.isEmpty();
        }
    }

    /**
     * "패키지명\n보여 줄 이름" 을 UTF-8 바이트로 만듭니다.
     *
     * <p>96바이트를 넘으면 <b>이름 쪽만</b> 줄입니다. 패키지명이 잘리면 앱을 아예
     * 못 여는 반면, 이름은 새 폰에서 다시 읽어 오면 되기 때문입니다.
     * 자를 때는 한글 한 글자가 반토막 나지 않도록 글자 경계에서 끊습니다.
     *
     * @return 보낼 바이트. 패키지명만으로도 한도를 넘으면 null.
     */
    public static byte[] encodeValue(String packageName, String label) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return new byte[0];              // 빈 슬롯도 "비었다"고 알려야 합니다
        }
        byte[] pkg = utf8(packageName.trim());
        if (pkg.length > MAX_VALUE) {
            return null;                     // 이런 패키지명은 실제로 없습니다
        }
        if (label == null || label.trim().isEmpty()) {
            return pkg;
        }
        int room = MAX_VALUE - pkg.length - 1;   // 1 = 줄바꿈
        if (room <= 0) {
            return pkg;
        }
        byte[] name = trimToUtf8Boundary(utf8(label.trim()), room);
        if (name.length == 0) {
            return pkg;
        }
        byte[] out = new byte[pkg.length + 1 + name.length];
        System.arraycopy(pkg, 0, out, 0, pkg.length);
        out[pkg.length] = (byte) '\n';
        System.arraycopy(name, 0, out, pkg.length + 1, name.length);
        return out;
    }

    /** 기기에서 온 바이트를 패키지명과 이름으로 되돌립니다. */
    public static Entry decodeValue(byte[] value) {
        if (value == null || value.length == 0) {
            return new Entry("", "");
        }
        int split = -1;
        for (int i = 0; i < value.length; i++) {
            if (value[i] == (byte) '\n') {
                split = i;
                break;
            }
        }
        if (split < 0) {
            return new Entry(fromUtf8(value, 0, value.length), "");
        }
        return new Entry(fromUtf8(value, 0, split),
                fromUtf8(value, split + 1, value.length - split - 1));
    }

    // ---------------------------------------------------------------- 보내는 쪽

    /** 값 하나를 보내려면 조각이 몇 개 필요한지. 빈 값도 "비었다"고 알려야 하므로 1개입니다. */
    public static int chunkCount(int valueLength) {
        if (valueLength <= 0) {
            return 1;
        }
        int count = (valueLength + CHUNK_DATA - 1) / CHUNK_DATA;
        return Math.min(count, MAX_CHUNKS);
    }

    /**
     * index 번째 조각의 payload 를 만듭니다.
     *
     * @return payload. slot 이나 index 가 범위를 벗어나면 null.
     */
    public static byte[] buildChunk(int slot, byte[] value, int index) {
        if (slot < 1 || slot > MAX_SLOTS || index < 0) {
            return null;
        }
        byte[] body = value == null ? new byte[0] : value;
        int count = chunkCount(body.length);
        if (index >= count) {
            return null;
        }
        int offset = index * CHUNK_DATA;
        int take = 0;
        if (offset < body.length) {
            take = Math.min(CHUNK_DATA, body.length - offset);
        }
        byte[] payload = new byte[HEADER_LEN + take];
        payload[0] = (byte) slot;
        payload[1] = (byte) index;
        payload[2] = (byte) count;
        System.arraycopy(body, offset, payload, HEADER_LEN, take);
        return payload;
    }

    // ---------------------------------------------------------------- 받는 쪽

    /**
     * 조각을 모아 값 하나로 되돌립니다.
     *
     * <p>슬롯마다 따로 모으기 때문에 1번을 받는 도중에 2번 조각이 끼어들어도 서로를
     * 망가뜨리지 않습니다. 다만 <b>한 슬롯 안에서는</b> 반드시 0번부터 차례여야 합니다.
     */
    public static final class Assembler {
        private final byte[][] partial = new byte[MAX_SLOTS][];
        private final int[] filled = new int[MAX_SLOTS];
        private final int[] next = new int[MAX_SLOTS];
        private final int[] count = new int[MAX_SLOTS];

        /**
         * 조각 하나를 넣습니다.
         *
         * @param data 머리말을 뺀 바이트 (없으면 null)
         * @return {@link #NEED_MORE} · {@link #COMPLETE} · {@link #REJECTED}
         */
        public int accept(int slot, int index, int total, byte[] data) {
            if (slot < 1 || slot > MAX_SLOTS) {
                return REJECTED;
            }
            int s = slot - 1;
            int len = data == null ? 0 : data.length;

            if (total < 1 || total > MAX_CHUNKS || index < 0 || index >= total
                    || len > CHUNK_DATA) {
                reset(s);
                return REJECTED;
            }
            if (index == 0) {
                partial[s] = new byte[MAX_VALUE];
                filled[s] = 0;
                count[s] = total;
                next[s] = 0;
            } else if (partial[s] == null || next[s] != index || count[s] != total) {
                reset(s);
                return REJECTED;    // 조각을 건너뛰었거나 도중에 전체 개수가 바뀌었습니다
            }
            if (filled[s] + len > MAX_VALUE) {
                reset(s);
                return REJECTED;
            }
            if (len > 0) {
                System.arraycopy(data, 0, partial[s], filled[s], len);
                filled[s] += len;
            }
            next[s] = index + 1;

            if (next[s] >= count[s]) {
                next[s] = 0;
                return COMPLETE;
            }
            return NEED_MORE;
        }

        /** 다 모인 값. accept 가 COMPLETE 를 돌려준 직후에 읽습니다. */
        public byte[] completed(int slot) {
            if (slot < 1 || slot > MAX_SLOTS || partial[slot - 1] == null) {
                return new byte[0];
            }
            int s = slot - 1;
            byte[] out = new byte[filled[s]];
            System.arraycopy(partial[s], 0, out, 0, filled[s]);
            return out;
        }

        public void resetAll() {
            for (int s = 0; s < MAX_SLOTS; s++) {
                reset(s);
            }
        }

        private void reset(int s) {
            partial[s] = null;
            filled[s] = 0;
            next[s] = 0;
            count[s] = 0;
        }
    }

    // ---------------------------------------------------------------- 글자 다루기

    /**
     * UTF-8 바이트를 한도에 맞게 자르되, 글자 중간에서 끊지 않습니다.
     * (한글 한 글자는 3바이트라 아무 데서나 자르면 깨진 글자가 남습니다)
     */
    static byte[] trimToUtf8Boundary(byte[] bytes, int limit) {
        if (bytes == null || limit <= 0) {
            return new byte[0];
        }
        if (bytes.length <= limit) {
            return bytes;
        }
        int end = limit;
        // 이어지는 바이트(10xxxxxx)면 글자 한가운데이므로 앞으로 물러납니다.
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        byte[] out = new byte[end];
        System.arraycopy(bytes, 0, out, 0, end);
        return out;
    }

    private static byte[] utf8(String text) {
        try {
            return text.getBytes(UTF8);
        } catch (UnsupportedEncodingException e) {
            return new byte[0];   // UTF-8 은 어느 자바에나 있습니다
        }
    }

    private static String fromUtf8(byte[] bytes, int offset, int length) {
        if (length <= 0) {
            return "";
        }
        try {
            return new String(bytes, offset, length, UTF8);
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    /** 값 하나를 프레임 payload 여러 개로 나눕니다. (보내는 쪽에서 그대로 쓰는 편의 함수) */
    public static byte[][] split(int slot, byte[] value) {
        int total = chunkCount(value == null ? 0 : value.length);
        byte[][] chunks = new byte[total][];
        for (int i = 0; i < total; i++) {
            chunks[i] = buildChunk(slot, value, i);
            if (chunks[i] == null) {
                return new byte[0][];
            }
        }
        return chunks;
    }

    /** 디버깅·기록용으로 바이트를 사람이 읽을 수 있는 글로 바꿉니다. */
    public static String describe(byte[] value) {
        Entry entry = decodeValue(value);
        if (entry.isEmpty()) {
            return "(비어 있음)";
        }
        return entry.label.isEmpty()
                ? entry.packageName
                : entry.label + " (" + entry.packageName + ")";
    }
}
