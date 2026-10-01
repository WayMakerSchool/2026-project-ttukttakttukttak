package com.example.vibekey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 기기가 보낸 신호를 제대로 해석하는지 검사합니다.
 * 이 부분이 틀리면 버튼을 눌러도 엉뚱한 앱이 열리므로 가장 중요한 검사입니다.
 *
 * 앞쪽은 현재 프레임(CRC·SEQ가 붙은 신호), 뒤쪽은 옛 평문 신호입니다.
 * 옛 펌웨어를 올린 기기도 그대로 써야 하므로 둘 다 남겨 두고 검사합니다.
 */
public class SignalParserTest {

    // ---------------------------------------------------------------- 현재 프레임

    /** 프레임 하나를 만들어 곧바로 해석까지 해 봅니다. */
    private static SignalParser.Result parseFrame(int seq, int button, int kind, int latencyUs) {
        byte[] payload = {(byte) button, (byte) kind,
                (byte) (latencyUs & 0xFF), (byte) ((latencyUs >> 8) & 0xFF)};
        byte[] bytes = FrameCodec.encode(seq, FrameCodec.T_EVT_PRESS, payload);
        final SignalParser.Result[] out = {SignalParser.parse(null)};
        new FrameCodec.Decoder().push(bytes, bytes.length, new FrameCodec.Sink() {
            @Override
            public void onFrame(FrameCodec.Frame frame) {
                out[0] = SignalParser.fromFrame(frame);
            }

            @Override
            public void onStrayBytes(byte[] bytes) {
            }
        });
        return out[0];
    }

    @Test
    public void 프레임의_버튼_번호를_읽는다() {
        assertEquals(1, parseFrame(10, 1, FrameCodec.K_SHORT, 0).slot);
        assertEquals(2, parseFrame(11, 2, FrameCodec.K_SHORT, 0).slot);
        assertEquals(3, parseFrame(12, 3, FrameCodec.K_SHORT, 0).slot);
    }

    @Test
    public void 프레임의_길게_누름은_AI_도우미를_연다() {
        SignalParser.Result result = parseFrame(13, 1, FrameCodec.K_LONG, 0);
        assertEquals(SignalParser.Action.OPEN_AI, result.action);
        assertFalse(result.isRunSlot());
    }

    /** 기기가 잰 지연을 그대로 들고 와야 자가진단 화면에 숫자로 보여 줄 수 있습니다. */
    @Test
    public void 프레임에_실린_지연_값을_그대로_가져온다() {
        SignalParser.Result result = parseFrame(14, 2, FrameCodec.K_SHORT, 1234);
        assertEquals(1234, result.latencyUs);
        assertEquals(14, result.seq);
        assertTrue(result.isFramed());
    }

    /** 없는 버튼 번호(예: 7번)가 실려 오면 아무 앱도 열지 않아야 합니다. */
    @Test
    public void 없는_버튼_번호는_실행하지_않는다() {
        assertEquals(SignalParser.Action.UNKNOWN, parseFrame(15, 7, FrameCodec.K_SHORT, 0).action);
        assertEquals(SignalParser.Action.UNKNOWN, parseFrame(16, 0, FrameCodec.K_SHORT, 0).action);
    }

    /** HELLO·STATS 처럼 버튼 누름이 아닌 프레임은 실행으로 이어지면 안 됩니다. */
    @Test
    public void 버튼_누름이_아닌_프레임은_실행하지_않는다() {
        byte[] hello = FrameCodec.encode(0, FrameCodec.T_HELLO, new byte[]{1, 3, 0, 3, 1});
        final SignalParser.Result[] out = {null};
        new FrameCodec.Decoder().push(hello, hello.length, new FrameCodec.Sink() {
            @Override
            public void onFrame(FrameCodec.Frame frame) {
                out[0] = SignalParser.fromFrame(frame);
            }

            @Override
            public void onStrayBytes(byte[] bytes) {
            }
        });
        assertEquals(SignalParser.Action.UNKNOWN, out[0].action);
    }

    @Test
    public void 프레임이_아닌_옛_신호는_시퀀스가_없다() {
        assertFalse(SignalParser.parse("Button1").isFramed());
        assertEquals(-1, SignalParser.parse("Button1").latencyUs);
    }

    // ---------------------------------------------------------------- 옛 평문

    @Test
    public void 옛날_펌웨어의_True_신호는_1번_버튼() {
        SignalParser.Result result = SignalParser.parse("True");
        assertTrue(result.isRunSlot());
        assertEquals(1, result.slot);
    }

    @Test
    public void 버튼_이름으로_보낸_신호를_읽는다() {
        assertEquals(1, SignalParser.parse("Button1").slot);
        assertEquals(2, SignalParser.parse("Button2").slot);
        assertEquals(3, SignalParser.parse("Button3").slot);
    }

    @Test
    public void 숫자만_보내도_읽는다() {
        assertEquals(1, SignalParser.parse("1").slot);
        assertEquals(2, SignalParser.parse("2").slot);
        assertEquals(3, SignalParser.parse("3").slot);
    }

    @Test
    public void 대소문자와_앞뒤_공백과_캐리지리턴을_무시한다() {
        assertEquals(2, SignalParser.parse("  bUtToN2\r ").slot);
        assertEquals(1, SignalParser.parse("\ttrue\r\n".trim()).slot);
    }

    @Test
    public void 길게_누름은_AI_도우미를_연다() {
        assertEquals(SignalParser.Action.OPEN_AI, SignalParser.parse("AI").action);
        assertEquals(SignalParser.Action.OPEN_AI, SignalParser.parse("ai").action);
        assertEquals(SignalParser.Action.OPEN_AI, SignalParser.parse("Long1").action);
        assertEquals(SignalParser.Action.OPEN_AI, SignalParser.parse("ButtonLong1").action);
    }

    /** "buttonlong1" 이 "button1" 로 잘못 읽히면 길게 눌러도 앱만 열려 버립니다. */
    @Test
    public void 길게_누름이_짧게_누름으로_잘못_읽히지_않는다() {
        SignalParser.Result result = SignalParser.parse("ButtonLong1");
        assertFalse(result.isRunSlot());
        assertEquals(0, result.slot);
    }

    @Test
    public void 알_수_없는_신호는_아무_일도_하지_않는다() {
        assertEquals(SignalParser.Action.UNKNOWN, SignalParser.parse("Button9").action);
        assertEquals(SignalParser.Action.UNKNOWN, SignalParser.parse("hello").action);
        assertEquals(SignalParser.Action.UNKNOWN, SignalParser.parse("").action);
        assertEquals(SignalParser.Action.UNKNOWN, SignalParser.parse("   ").action);
        assertEquals(SignalParser.Action.UNKNOWN, SignalParser.parse(null).action);
    }

    /**
     * 시리얼에는 잡음이 섞이므로, 못 알아듣는 값이 와도 예외 없이 넘어가야 합니다.
     * (널 문자 \0 처럼 글자가 아닌 바이트도 그냥 무시되어야 합니다.)
     */
    @Test
    public void 잡음이_들어와도_예외가_나지_않는다() {
        assertEquals(SignalParser.Action.UNKNOWN, SignalParser.parse("\0\u00FF??").action);
    }
}
