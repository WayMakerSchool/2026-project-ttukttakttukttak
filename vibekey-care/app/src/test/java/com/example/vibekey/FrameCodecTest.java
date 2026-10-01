package com.example.vibekey;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 프레임 프로토콜 검사.
 *
 * 이 앱에서 가장 위험한 실패는 "안 되는 것"이 아니라 "엉뚱하게 되는 것"입니다.
 * 버튼 한 번이 택시 호출로 이어지기 때문에, 깨진 신호가 실행으로 새어 나가면
 * 곧바로 요금이 발생합니다. 그래서 여기서는 "제대로 읽는가"보다
 * <b>"깨진 것을 반드시 버리는가"</b>를 더 많이 검사합니다.
 *
 * 펌웨어 쪽 같은 검사: firmware/test/test_vkp.cpp (PC에서 ./run_tests.sh 로 실행)
 */
public class FrameCodecTest {

    /** 프레임을 모아 두는 간단한 수집기 */
    private static class Collector implements FrameCodec.Sink {
        final List<FrameCodec.Frame> frames = new ArrayList<>();
        final ByteArrayOutputStream stray = new ByteArrayOutputStream();

        @Override
        public void onFrame(FrameCodec.Frame frame) {
            frames.add(frame);
        }

        @Override
        public void onStrayBytes(byte[] bytes) {
            stray.write(bytes, 0, bytes.length);
        }
    }

    // ---------------------------------------------------------------- CRC

    @Test
    public void CRC16_이_펌웨어와_같은_값을_낸다() {
        // CRC-16/CCITT-FALSE 의 널리 알려진 검증값. 이 값이 어긋나면 기기와 말이 안 통합니다.
        byte[] check = "123456789".getBytes();
        assertEquals(0x29B1, FrameCodec.crc16(check, 0, check.length));
    }

    @Test
    public void 한_비트만_달라도_CRC가_달라진다() {
        byte[] a = {0x01, 0x02, 0x03};
        byte[] b = {0x01, 0x02, 0x02};
        assertTrue(FrameCodec.crc16(a, 0, 3) != FrameCodec.crc16(b, 0, 3));
    }

    // ---------------------------------------------------------------- 왕복

    @Test
    public void 만든_프레임을_그대로_다시_읽는다() {
        byte[] payload = {2, (byte) FrameCodec.K_SHORT, 0x34, 0x12};
        byte[] bytes = FrameCodec.encode(77, FrameCodec.T_EVT_PRESS, payload);
        assertNotNull(bytes);
        assertEquals(11, bytes.length);          // 머리 4 + 본문 4 + CRC 2 + 끝 1

        Collector sink = new Collector();
        new FrameCodec.Decoder().push(bytes, bytes.length, sink);

        assertEquals(1, sink.frames.size());
        FrameCodec.Frame f = sink.frames.get(0);
        assertEquals(77, f.seq);
        assertEquals(FrameCodec.T_EVT_PRESS, f.type);
        assertEquals(2, f.u8(0));
        assertEquals(0x1234, f.u16(2));          // 리틀엔디언
        assertEquals(0, sink.stray.size());
    }

    @Test
    public void 빈_payload_프레임도_주고받는다() {
        byte[] bytes = FrameCodec.encode(0, FrameCodec.T_PING, null);
        assertNotNull(bytes);
        assertEquals(7, bytes.length);

        Collector sink = new Collector();
        new FrameCodec.Decoder().push(bytes, bytes.length, sink);
        assertEquals(1, sink.frames.size());
        assertEquals(FrameCodec.T_PING, sink.frames.get(0).type);
    }

    @Test
    public void payload가_한도를_넘으면_만들지_않는다() {
        assertNull(FrameCodec.encode(1, FrameCodec.T_STATS, new byte[FrameCodec.MAX_PAYLOAD + 1]));
    }

    // ---------------------------------------------------------------- 깨진 신호

    /**
     * 가장 중요한 검사입니다. 프레임의 모든 비트를 하나씩 뒤집어 보며
     * (11바이트 × 8비트 = 88가지) 단 한 번도 "원래 신호"로 통과하지 않는지 확인합니다.
     */
    @Test
    public void 비트_하나가_뒤집힌_프레임은_하나도_실행되지_않는다() {
        byte[] payload = {1, (byte) FrameCodec.K_SHORT, 0x10, 0x00};
        byte[] original = FrameCodec.encode(42, FrameCodec.T_EVT_PRESS, payload);
        assertNotNull(original);

        int leaked = 0;
        for (int byteIdx = 0; byteIdx < original.length; byteIdx++) {
            for (int bit = 0; bit < 8; bit++) {
                byte[] corrupted = original.clone();
                corrupted[byteIdx] ^= (byte) (1 << bit);

                Collector sink = new Collector();
                new FrameCodec.Decoder().push(corrupted, corrupted.length, sink);
                for (FrameCodec.Frame f : sink.frames) {
                    if (f.seq == 42 && f.type == FrameCodec.T_EVT_PRESS && f.u8(0) == 1) {
                        leaked++;
                    }
                }
            }
        }
        assertEquals("깨진 프레임이 원래 신호로 통과함", 0, leaked);
    }

    /**
     * 무작위로 여러 비트를 망가뜨려도 "버튼 누름"으로 새어 나가지 않아야 합니다.
     * 씨앗을 고정해 두어 언제 돌려도 같은 결과가 나옵니다.
     */
    @Test
    public void 무작위로_망가진_프레임_2만개가_모두_걸러진다() {
        Random random = new Random(20260903L);
        int leaked = 0;

        for (int trial = 0; trial < 20000; trial++) {
            int button = 1 + random.nextInt(3);
            byte[] payload = {(byte) button, (byte) FrameCodec.K_SHORT,
                    (byte) random.nextInt(256), 0};
            byte[] bytes = FrameCodec.encode(1 + random.nextInt(255),
                    FrameCodec.T_EVT_PRESS, payload);
            assertNotNull(bytes);

            // 같은 비트를 두 번 뒤집으면 원래대로 돌아가므로 겹치지 않게 고릅니다.
            int flips = 1 + random.nextInt(4);
            boolean[][] used = new boolean[bytes.length][8];
            int done = 0;
            while (done < flips) {
                int pos = random.nextInt(bytes.length);
                int bit = random.nextInt(8);
                if (used[pos][bit]) {
                    continue;
                }
                used[pos][bit] = true;
                done++;
                bytes[pos] ^= (byte) (1 << bit);
            }

            Collector sink = new Collector();
            new FrameCodec.Decoder().push(bytes, bytes.length, sink);
            for (FrameCodec.Frame f : sink.frames) {
                SignalParser.Result r = SignalParser.fromFrame(f);
                if (r.isRunSlot()) {
                    leaked++;
                }
            }
        }
        assertEquals("망가진 프레임이 버튼 실행으로 이어짐", 0, leaked);
    }

    @Test
    public void 잘려_들어온_프레임은_실행되지_않고_다음_프레임은_정상_처리된다() {
        byte[] bytes = FrameCodec.encode(5, FrameCodec.T_EVT_PRESS,
                new byte[]{1, (byte) FrameCodec.K_SHORT});
        assertNotNull(bytes);

        FrameCodec.Decoder decoder = new FrameCodec.Decoder();
        Collector sink = new Collector();

        // 케이블을 꽂는 순간처럼 프레임이 중간에서 잘려 들어온 경우
        decoder.push(bytes, bytes.length - 2, sink);
        assertEquals(0, sink.frames.size());

        decoder.push(bytes, bytes.length, sink);
        assertEquals(1, sink.frames.size());
    }

    @Test
    public void 여러_번에_나뉘어_와도_한_프레임으로_모은다() {
        byte[] bytes = FrameCodec.encode(9, FrameCodec.T_EVT_PRESS,
                new byte[]{3, (byte) FrameCodec.K_SHORT});
        assertNotNull(bytes);

        FrameCodec.Decoder decoder = new FrameCodec.Decoder();
        Collector sink = new Collector();
        for (byte b : bytes) {
            decoder.push(new byte[]{b}, 1, sink);   // 한 바이트씩
        }
        assertEquals(1, sink.frames.size());
        assertEquals(3, sink.frames.get(0).u8(0));
    }

    @Test
    public void 붙어서_온_두_프레임을_둘_다_읽는다() {
        byte[] a = FrameCodec.encode(1, FrameCodec.T_EVT_PRESS, new byte[]{1, 0});
        byte[] b = FrameCodec.encode(2, FrameCodec.T_EVT_PRESS, new byte[]{2, 0});
        assertNotNull(a);
        assertNotNull(b);

        byte[] joined = new byte[a.length + b.length];
        System.arraycopy(a, 0, joined, 0, a.length);
        System.arraycopy(b, 0, joined, a.length, b.length);

        Collector sink = new Collector();
        new FrameCodec.Decoder().push(joined, joined.length, sink);
        assertEquals(2, sink.frames.size());
        assertEquals(1, sink.frames.get(0).u8(0));
        assertEquals(2, sink.frames.get(1).u8(0));
    }

    // ---------------------------------------------------------------- 옛 펌웨어 호환

    /**
     * 옛 펌웨어("True\n")를 올린 기기도 그대로 써야 합니다.
     * 프레임이 아닌 바이트는 버려지지 않고 그대로 넘어와야, 예전 방식으로 한 번 더 해석할 수 있습니다.
     */
    @Test
    public void 옛_평문_신호는_그대로_넘어온다() {
        byte[] legacy = "True\nButton2\n".getBytes();

        Collector sink = new Collector();
        new FrameCodec.Decoder().push(legacy, legacy.length, sink);

        assertEquals(0, sink.frames.size());
        assertArrayEquals(legacy, sink.stray.toByteArray());
    }

    @Test
    public void 잡음_뒤에_온_온전한_프레임을_되찾는다() {
        byte[] good = FrameCodec.encode(9, FrameCodec.T_EVT_PRESS,
                new byte[]{3, (byte) FrameCodec.K_SHORT});
        assertNotNull(good);

        // 가짜 시작 바이트(0xAA)와 쓰레기를 잔뜩 앞에 붙입니다.
        byte[] noise = {(byte) 0xAA, (byte) 0xFF, 0x00, (byte) 0xAA, 0x11, 0x22};
        byte[] stream = new byte[noise.length + good.length];
        System.arraycopy(noise, 0, stream, 0, noise.length);
        System.arraycopy(good, 0, stream, noise.length, good.length);

        Collector sink = new Collector();
        FrameCodec.Decoder decoder = new FrameCodec.Decoder();
        decoder.push(stream, stream.length, sink);

        assertEquals(1, sink.frames.size());
        assertEquals(9, sink.frames.get(0).seq);
        assertTrue("버린 바이트를 세어 둬야 오류율을 낼 수 있다", decoder.discarded > 0);
        assertTrue(decoder.errorRate() > 0.0);
    }
}
