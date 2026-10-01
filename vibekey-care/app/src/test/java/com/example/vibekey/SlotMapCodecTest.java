package com.example.vibekey;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.UnsupportedEncodingException;

/**
 * 버튼 매핑을 기기에 담아 두는 코덱을 검사합니다.
 *
 * <p>여기서 가장 나쁜 결과는 "절반만 저장된 매핑"입니다. 화면을 안 보고 누르는
 * 기기라서, 1번 버튼에 엉뚱한 앱이 붙어 있어도 누르기 전까지는 알 수 없습니다.
 * 그래서 조각이 하나라도 어긋나면 통째로 버리는지를 특히 꼼꼼히 봅니다.
 *
 * <p>펌웨어 쪽 같은 검사는 {@code firmware/test/test_vkp.cpp} 에 있습니다.
 */
public class SlotMapCodecTest {

    private static final String DIALER = "com.samsung.android.dialer";

    private static byte[] utf8(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    /** 조각내어 보낸 뒤 그대로 다시 모읍니다. */
    private static byte[] roundTrip(int slot, byte[] value) {
        SlotMapCodec.Assembler assembler = new SlotMapCodec.Assembler();
        byte[][] chunks = SlotMapCodec.split(slot, value);
        assertTrue("조각이 하나도 안 나왔습니다", chunks.length > 0);

        int last = SlotMapCodec.NEED_MORE;
        for (int i = 0; i < chunks.length; i++) {
            byte[] payload = chunks[i];
            byte[] data = new byte[payload.length - SlotMapCodec.HEADER_LEN];
            System.arraycopy(payload, SlotMapCodec.HEADER_LEN, data, 0, data.length);
            last = assembler.accept(payload[0] & 0xFF, payload[1] & 0xFF,
                    payload[2] & 0xFF, data);
            if (i + 1 < chunks.length) {
                assertEquals("아직 안 끝났어야 합니다", SlotMapCodec.NEED_MORE, last);
            }
        }
        assertEquals("마지막 조각에서 완성돼야 합니다", SlotMapCodec.COMPLETE, last);
        return assembler.completed(slot);
    }

    // ---------------------------------------------------------------- 값 만들기

    @Test
    public void 패키지명과_이름을_한_줄로_담는다() {
        byte[] value = SlotMapCodec.encodeValue(DIALER, "전화");
        SlotMapCodec.Entry entry = SlotMapCodec.decodeValue(value);

        assertEquals(DIALER, entry.packageName);
        assertEquals("전화", entry.label);
    }

    @Test
    public void 이름이_없으면_패키지명만_담는다() {
        SlotMapCodec.Entry entry =
                SlotMapCodec.decodeValue(SlotMapCodec.encodeValue(DIALER, ""));
        assertEquals(DIALER, entry.packageName);
        assertEquals("", entry.label);

        entry = SlotMapCodec.decodeValue(SlotMapCodec.encodeValue(DIALER, null));
        assertEquals(DIALER, entry.packageName);
    }

    @Test
    public void 빈_버튼도_비었다고_알린다() {
        byte[] value = SlotMapCodec.encodeValue("", "아무거나");
        assertEquals(0, value.length);
        assertTrue(SlotMapCodec.decodeValue(value).isEmpty());
        // 빈 값도 반드시 한 조각으로 전달돼야 "이 버튼을 비웠다"가 기기에 전해집니다.
        assertEquals(1, SlotMapCodec.chunkCount(0));
    }

    @Test
    public void 이름이_길면_이름만_줄이고_패키지명은_지킨다() {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            longName.append("가");     // 한 글자에 3바이트
        }
        byte[] value = SlotMapCodec.encodeValue(DIALER, longName.toString());

        assertNotNull(value);
        assertTrue("한도를 넘었습니다", value.length <= SlotMapCodec.MAX_VALUE);

        SlotMapCodec.Entry entry = SlotMapCodec.decodeValue(value);
        // 패키지명이 잘리면 앱을 아예 못 엽니다. 이름은 줄어도 다시 읽어 오면 됩니다.
        assertEquals(DIALER, entry.packageName);
        assertTrue(entry.label.length() > 0);
        assertTrue(entry.label.length() < 80);
    }

    @Test
    public void 한글이_반토막_나지_않게_자른다() {
        byte[] three = utf8("가");                 // 3바이트
        assertEquals(0, SlotMapCodec.trimToUtf8Boundary(three, 1).length);
        assertEquals(0, SlotMapCodec.trimToUtf8Boundary(three, 2).length);
        assertEquals(3, SlotMapCodec.trimToUtf8Boundary(three, 3).length);

        byte[] two = utf8("가나");                 // 6바이트
        assertEquals(3, SlotMapCodec.trimToUtf8Boundary(two, 4).length);
        assertEquals(3, SlotMapCodec.trimToUtf8Boundary(two, 5).length);
        assertEquals(6, SlotMapCodec.trimToUtf8Boundary(two, 6).length);
    }

    @Test
    public void 담을_수_없이_긴_패키지명은_거절한다() {
        StringBuilder absurd = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            absurd.append("com.");
        }
        assertNull(SlotMapCodec.encodeValue(absurd.toString(), "이름"));
    }

    // ---------------------------------------------------------------- 조각내기 / 모으기

    @Test
    public void 조각내어_보낸_뒤_그대로_모인다() {
        byte[] value = SlotMapCodec.encodeValue(DIALER, "전화");
        assertTrue("이 값은 한 조각에 안 들어갑니다", value.length > SlotMapCodec.CHUNK_DATA);
        assertArrayEquals(value, roundTrip(1, value));
    }

    @Test
    public void 한도까지_꽉_채워도_모인다() {
        byte[] big = new byte[SlotMapCodec.MAX_VALUE];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) ('a' + (i % 26));
        }
        assertArrayEquals(big, roundTrip(2, big));
        assertTrue(SlotMapCodec.chunkCount(SlotMapCodec.MAX_VALUE) <= SlotMapCodec.MAX_CHUNKS);
    }

    @Test
    public void 모든_조각이_프레임_한도_안에_들어간다() {
        byte[] big = new byte[SlotMapCodec.MAX_VALUE];
        for (byte[] chunk : SlotMapCodec.split(1, big)) {
            assertTrue("payload 한도를 넘었습니다", chunk.length <= FrameCodec.MAX_PAYLOAD);
            assertNotNull("프레임으로 감쌀 수 없습니다",
                    FrameCodec.encode(0, FrameCodec.T_SET_MAP, chunk));
        }
    }

    @Test
    public void 조각을_건너뛰면_버린다() {
        SlotMapCodec.Assembler assembler = new SlotMapCodec.Assembler();
        byte[] data = new byte[SlotMapCodec.CHUNK_DATA];

        assertEquals(SlotMapCodec.NEED_MORE, assembler.accept(1, 0, 3, data));
        assertEquals(SlotMapCodec.REJECTED, assembler.accept(1, 2, 3, data));
        // 버린 다음에는 이어 붙이지 않고, 0번부터 다시 시작해야 합니다.
        assertEquals(SlotMapCodec.REJECTED, assembler.accept(1, 1, 3, data));
        assertEquals(SlotMapCodec.COMPLETE, assembler.accept(1, 0, 1, data));
    }

    @Test
    public void 말이_안_되는_머리말은_버린다() {
        SlotMapCodec.Assembler assembler = new SlotMapCodec.Assembler();
        byte[] data = new byte[4];

        assertEquals(SlotMapCodec.REJECTED, assembler.accept(0, 0, 1, data));
        assertEquals(SlotMapCodec.REJECTED, assembler.accept(9, 0, 1, data));
        assertEquals(SlotMapCodec.REJECTED, assembler.accept(1, 0, 0, data));
        assertEquals(SlotMapCodec.REJECTED,
                assembler.accept(1, 0, SlotMapCodec.MAX_CHUNKS + 1, data));
        assertEquals(SlotMapCodec.REJECTED, assembler.accept(1, 3, 3, data));
        assertEquals(SlotMapCodec.REJECTED,
                assembler.accept(1, 0, 1, new byte[SlotMapCodec.CHUNK_DATA + 1]));
    }

    @Test
    public void 도중에_전체_개수가_바뀌면_버린다() {
        SlotMapCodec.Assembler assembler = new SlotMapCodec.Assembler();
        byte[] data = new byte[SlotMapCodec.CHUNK_DATA];

        assertEquals(SlotMapCodec.NEED_MORE, assembler.accept(2, 0, 3, data));
        assertEquals(SlotMapCodec.REJECTED, assembler.accept(2, 1, 2, data));
    }

    @Test
    public void 버튼끼리_서로를_망가뜨리지_않는다() {
        SlotMapCodec.Assembler assembler = new SlotMapCodec.Assembler();
        byte[] a = new byte[SlotMapCodec.CHUNK_DATA];
        byte[] b = new byte[5];
        for (int i = 0; i < a.length; i++) {
            a[i] = 'A';
        }
        for (int i = 0; i < b.length; i++) {
            b[i] = 'B';
        }

        assertEquals(SlotMapCodec.NEED_MORE, assembler.accept(1, 0, 2, a));
        assertEquals(SlotMapCodec.COMPLETE, assembler.accept(2, 0, 1, b));
        assertEquals(5, assembler.completed(2).length);

        assertEquals(SlotMapCodec.COMPLETE, assembler.accept(1, 1, 2, new byte[3]));
        byte[] first = assembler.completed(1);
        assertEquals(SlotMapCodec.CHUNK_DATA + 3, first.length);
        assertEquals('A', first[0]);
    }

    @Test
    public void 조각이_빠지면_절대_완성되지_않는다() {
        // CRC에서 걸려 조각 하나가 통째로 사라진 경우입니다.
        byte[] value = SlotMapCodec.encodeValue("com.kakao.talk", "카카오톡");
        byte[][] chunks = SlotMapCodec.split(1, value);
        assertTrue("이 값은 조각이 셋 이상이어야 합니다", chunks.length >= 3);

        SlotMapCodec.Assembler assembler = new SlotMapCodec.Assembler();
        boolean sawComplete = false;
        for (int i = 0; i < chunks.length; i++) {
            if (i == 1) {
                continue;       // 두 번째 조각이 사라졌습니다
            }
            byte[] payload = chunks[i];
            byte[] data = new byte[payload.length - SlotMapCodec.HEADER_LEN];
            System.arraycopy(payload, SlotMapCodec.HEADER_LEN, data, 0, data.length);
            if (assembler.accept(payload[0] & 0xFF, payload[1] & 0xFF, payload[2] & 0xFF, data)
                    == SlotMapCodec.COMPLETE) {
                sawComplete = true;
            }
        }
        assertTrue("절반짜리 매핑이 저장될 뻔했습니다", !sawComplete);
    }

    @Test
    public void 기록용_설명을_만든다() {
        assertEquals("전화 (" + DIALER + ")",
                SlotMapCodec.describe(SlotMapCodec.encodeValue(DIALER, "전화")));
        assertEquals(DIALER, SlotMapCodec.describe(SlotMapCodec.encodeValue(DIALER, "")));
        assertEquals("(비어 있음)", SlotMapCodec.describe(new byte[0]));
        assertEquals("(비어 있음)", SlotMapCodec.describe(null));
    }

    @Test
    public void 펌웨어와_규격_숫자가_같다() {
        // firmware/vibekey_firmware/slot_store.h 와 반드시 일치해야 합니다.
        assertEquals(3, SlotMapCodec.MAX_SLOTS);
        assertEquals(96, SlotMapCodec.MAX_VALUE);
        assertEquals(13, SlotMapCodec.CHUNK_DATA);
        assertEquals(3, SlotMapCodec.HEADER_LEN);
        assertEquals(8, SlotMapCodec.MAX_CHUNKS);
        assertEquals(FrameCodec.MAX_PAYLOAD,
                SlotMapCodec.HEADER_LEN + SlotMapCodec.CHUNK_DATA);
    }
}
