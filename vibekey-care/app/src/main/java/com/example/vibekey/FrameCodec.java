package com.example.vibekey;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/**
 * 기기(ESP32-S3)와 주고받는 프레임 프로토콜 VKP v1 을 다루는 코덱입니다.
 *
 * <p>개발 초기에는 기기가 {@code "True\n"} 같은 평문 한 줄을 보냈습니다. 이 방식에는
 * 프레임 경계도, 오류 검출도, 중복 방지도 없어서, 화면으로 확인하지 않는 사용자에게
 * <b>잡음 한 번이 그대로 앱 실행</b>이 될 수 있었습니다. 그래서 아래 형식으로 바꿨습니다.
 *
 * <pre>
 *   0xAA | SEQ | TYPE | LEN | PAYLOAD[LEN] | CRC16_L | CRC16_H | 0x55
 *    (1)   (1)   (1)    (1)     (0~16)         (1)       (1)      (1)
 * </pre>
 *
 * <ul>
 *   <li>CRC16 (CCITT-FALSE, 0x1021 / init 0xFFFF) 을 SEQ~PAYLOAD 위에서 계산합니다.</li>
 *   <li>SEQ 로 같은 누름을 두 번 실행하지 않습니다. 기기는 ACK를 못 받으면 <b>같은 SEQ로</b>
 *       다시 보내므로, 폰은 "받았지만 이미 처리한 것"을 구분할 수 있습니다.</li>
 *   <li>중간부터 끊겨 들어와도 다음 0xAA를 찾아 재동기화하고, 버린 바이트를 세어 둡니다.</li>
 * </ul>
 *
 * <p>펌웨어의 {@code firmware/vibekey_firmware/vkp_frame.h} 와 규격이 완전히 같습니다.
 * 안드로이드 클래스를 쓰지 않는 순수 자바라 {@code ./gradlew test} 로 바로 검증됩니다.
 * (app/src/test/java/com/example/vibekey/FrameCodecTest.java)
 */
public final class FrameCodec {

    public static final int STX = 0xAA;
    public static final int ETX = 0x55;
    public static final int MAX_PAYLOAD = 16;
    public static final int OVERHEAD = 7;                       // STX+SEQ+TYPE+LEN+CRC(2)+ETX
    public static final int MAX_FRAME = MAX_PAYLOAD + OVERHEAD;
    public static final int PROTO_VERSION = 1;

    // 기기 → 폰
    public static final int T_EVT_PRESS = 0x01;
    public static final int T_HELLO     = 0x02;
    public static final int T_STATS     = 0x03;
    public static final int T_MAP       = 0x04;   // 기기에 저장된 버튼 매핑 (조각 단위)
    public static final int T_STATS2    = 0x05;   // 주머니 오작동 차단 집계

    // 폰 → 기기
    public static final int T_ACK       = 0x10;
    public static final int T_PING      = 0x12;
    // 0x11 은 예전에 진동 패턴 지시(FEEDBACK)로 쓰던 번호입니다. 기기에 출력 장치가
    // 없어 지금은 쓰지 않지만, 나중에 표시 장치를 붙일 때를 위해 비워 둡니다.
    public static final int T_SET_MAP   = 0x13;   // 이 버튼 매핑을 기기에 저장해 둬
    public static final int T_GET_MAP   = 0x14;   // 기기가 저장해 둔 매핑을 알려 줘

    /** HELLO 의 caps 비트: 기기가 버튼 매핑을 저장할 수 있음 */
    public static final int CAP_SLOT_STORE = 0x01;

    // 누름 종류
    public static final int K_SHORT  = 0;
    public static final int K_LONG   = 1;
    public static final int K_DOUBLE = 2;

    private FrameCodec() {
    }

    // ---------------------------------------------------------------- CRC16

    /** CRC-16/CCITT-FALSE. 펌웨어의 crc16() 과 같은 값을 내야 합니다. */
    public static int crc16(byte[] data, int offset, int length) {
        int crc = 0xFFFF;
        for (int i = offset; i < offset + length; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (int b = 0; b < 8; b++) {
                crc = ((crc & 0x8000) != 0) ? ((crc << 1) ^ 0x1021) : (crc << 1);
                crc &= 0xFFFF;
            }
        }
        return crc;
    }

    // ---------------------------------------------------------------- 프레임

    /** 받은 프레임 하나. */
    public static final class Frame {
        public final int seq;
        public final int type;
        public final byte[] payload;

        Frame(int seq, int type, byte[] payload) {
            this.seq = seq;
            this.type = type;
            this.payload = payload;
        }

        public int len() {
            return payload.length;
        }

        /** payload 의 i번째 바이트 (없으면 0) */
        public int u8(int i) {
            return (i >= 0 && i < payload.length) ? (payload[i] & 0xFF) : 0;
        }

        /** payload 의 i번째부터 리틀엔디언 2바이트 */
        public int u16(int i) {
            return u8(i) | (u8(i + 1) << 8);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT, "seq=%d type=0x%02X [", seq, type));
            for (int i = 0; i < payload.length; i++) {
                if (i > 0) {
                    sb.append(' ');
                }
                sb.append(String.format(Locale.ROOT, "%02X", payload[i] & 0xFF));
            }
            return sb.append(']').toString();
        }
    }

    /**
     * 보낼 프레임을 바이트로 만듭니다.
     *
     * @param seq     1~255 (0은 "답을 기다리지 않는 알림")
     * @param payload null 이면 빈 payload
     * @return 완성된 바이트. payload 가 한도를 넘으면 null.
     */
    public static byte[] encode(int seq, int type, byte[] payload) {
        byte[] body = (payload == null) ? new byte[0] : payload;
        if (body.length > MAX_PAYLOAD) {
            return null;
        }
        byte[] out = new byte[body.length + OVERHEAD];
        int n = 0;
        out[n++] = (byte) STX;
        out[n++] = (byte) (seq & 0xFF);
        out[n++] = (byte) (type & 0xFF);
        out[n++] = (byte) body.length;
        System.arraycopy(body, 0, out, n, body.length);
        n += body.length;
        int crc = crc16(out, 1, 3 + body.length);
        out[n++] = (byte) (crc & 0xFF);
        out[n++] = (byte) ((crc >> 8) & 0xFF);
        out[n] = (byte) ETX;
        return out;
    }

    // ---------------------------------------------------------------- 디코더

    /** 디코더가 알려 주는 두 가지: 온전한 프레임과, 프레임이 아니었던 바이트. */
    public interface Sink {
        void onFrame(Frame frame);

        /**
         * 프레임 밖에서 버려진 바이트입니다.
         * 옛 펌웨어의 평문("True\n")도 여기로 오기 때문에, 이 값을 그대로
         * 예전 방식으로 한 번 더 해석해 주면 <b>펌웨어를 안 올린 기기도 그대로 동작</b>합니다.
         */
        void onStrayBytes(byte[] bytes);
    }

    /**
     * 바이트를 넣으면 온전한 프레임만 골라 내는 상태 기계입니다.
     * 시리얼은 한 프레임이 여러 번에 나뉘어 오거나 여러 프레임이 붙어 오므로,
     * 도착한 조각을 그대로 밀어 넣으면 됩니다.
     */
    public static final class Decoder {
        private final byte[] buf = new byte[MAX_FRAME];
        private int n = 0;

        /** 정상 처리한 프레임 수 */
        public long accepted;
        /** CRC가 안 맞아 버린 프레임 수 */
        public long crcErrors;
        /** 길이·끝바이트가 틀려 버린 프레임 수 */
        public long framingErrors;
        /** 프레임 밖에서 버려진 바이트 수 */
        public long discarded;
        /** 들어온 전체 바이트 수 (오류율의 분모) */
        public long totalBytes;

        public void reset() {
            n = 0;
        }

        public void push(byte[] data, int length, Sink sink) {
            totalBytes += length;
            ByteArrayOutputStream stray = new ByteArrayOutputStream();
            for (int i = 0; i < length; i++) {
                if (n == MAX_FRAME) {
                    // 여기까지 왔다는 건 앞머리가 0xAA인데 끝이 안 맞는 경우입니다.
                    stray.write(buf[0]);
                    discarded++;
                    shift(1);
                }
                buf[n++] = data[i];
                scan(sink, stray);
            }
            if (stray.size() > 0 && sink != null) {
                sink.onStrayBytes(stray.toByteArray());
            }
        }

        /** 지금까지 들어온 바이트 중 버려진 비율 (0.0 ~ 1.0). 보고서의 "프레임 오류율". */
        public double errorRate() {
            return totalBytes == 0 ? 0.0 : (double) discarded / (double) totalBytes;
        }

        private void shift(int count) {
            if (count >= n) {
                n = 0;
                return;
            }
            System.arraycopy(buf, count, buf, 0, n - count);
            n -= count;
        }

        private void scan(Sink sink, ByteArrayOutputStream stray) {
            while (n > 0) {
                if ((buf[0] & 0xFF) != STX) {
                    stray.write(buf[0]);
                    discarded++;
                    shift(1);
                    continue;
                }
                if (n < 4) {
                    return;                         // 머리말이 아직 덜 왔음
                }
                int len = buf[3] & 0xFF;
                if (len > MAX_PAYLOAD) {            // 길이가 말이 안 됨 → 가짜 0xAA였음
                    framingErrors++;
                    stray.write(buf[0]);
                    discarded++;
                    shift(1);
                    continue;
                }
                int total = len + OVERHEAD;
                if (n < total) {
                    return;                         // 본문이 아직 덜 왔음
                }
                int crc = crc16(buf, 1, 3 + len);
                if ((buf[total - 3] & 0xFF) != (crc & 0xFF)
                        || (buf[total - 2] & 0xFF) != ((crc >> 8) & 0xFF)) {
                    crcErrors++;
                    stray.write(buf[0]);
                    discarded++;
                    shift(1);
                    continue;
                }
                if ((buf[total - 1] & 0xFF) != ETX) {
                    framingErrors++;
                    stray.write(buf[0]);
                    discarded++;
                    shift(1);
                    continue;
                }
                byte[] payload = new byte[len];
                System.arraycopy(buf, 4, payload, 0, len);
                Frame frame = new Frame(buf[1] & 0xFF, buf[2] & 0xFF, payload);
                shift(total);
                accepted++;
                if (sink != null) {
                    sink.onFrame(frame);
                }
            }
        }
    }
}
