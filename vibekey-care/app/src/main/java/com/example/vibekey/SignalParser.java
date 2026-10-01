package com.example.vibekey;

import java.util.Locale;

/**
 * 기기(ESP32-S3)가 보낸 신호를 "무엇을 하라는 뜻인지"로 바꿉니다.
 *
 * 두 가지 경로를 모두 받습니다.
 * <ul>
 *   <li><b>프레임</b> {@link #fromFrame(FrameCodec.Frame)} — 현재 펌웨어. CRC·SEQ가 붙어 있어
 *       깨진 신호가 실행으로 이어지지 않습니다. 어느 버튼인지, 짧게인지 길게인지,
 *       접점에서 송출까지 몇 마이크로초 걸렸는지까지 함께 옵니다.</li>
 *   <li><b>평문 한 줄</b> {@link #parse(String)} — 옛 평문 펌웨어("True", "Button1", "AI").
 *       펌웨어를 아직 안 올린 기기도 그대로 쓸 수 있도록 남겨 두었습니다.</li>
 * </ul>
 *
 * 안드로이드 클래스를 쓰지 않는 순수 자바 로직이라 단위 테스트로 바로 검증할 수 있습니다.
 * (app/src/test/java/com/example/vibekey/SignalParserTest.java)
 */
public final class SignalParser {

    /** 신호 한 줄이 뜻하는 동작 */
    public enum Action {
        /** 1·2·3번 버튼에 연결된 앱 실행 */
        RUN_SLOT,
        /** AI 도우미 열기 (버튼 길게 누름) */
        OPEN_AI,
        /** 알아듣지 못한 신호 */
        UNKNOWN
    }

    /** 해석 결과. RUN_SLOT 일 때만 slot(1~3) 값이 뜻이 있습니다. */
    public static final class Result {
        public final Action action;
        public final int slot;

        /** 프레임으로 왔을 때의 시퀀스 번호. 평문이면 0. 같은 번호는 두 번 실행하지 않습니다. */
        public final int seq;
        /** 누름 종류 (FrameCodec.K_SHORT / K_LONG / K_DOUBLE). 평문이면 K_SHORT. */
        public final int kind;
        /** 기기가 잰 "접점 → USB 송출" 지연(us). 평문이면 -1(잰 적 없음). */
        public final int latencyUs;

        Result(Action action, int slot) {
            this(action, slot, 0, FrameCodec.K_SHORT, -1);
        }

        Result(Action action, int slot, int seq, int kind, int latencyUs) {
            this.action = action;
            this.slot = slot;
            this.seq = seq;
            this.kind = kind;
            this.latencyUs = latencyUs;
        }

        public boolean isRunSlot() {
            return action == Action.RUN_SLOT;
        }

        /** 프레임으로 온 신호인가 (SEQ가 붙어 있으면 프레임) */
        public boolean isFramed() {
            return seq != 0;
        }

        @Override
        public String toString() {
            return action == Action.RUN_SLOT ? "RUN_SLOT(" + slot + ")" : action.name();
        }
    }

    private static final Result UNKNOWN = new Result(Action.UNKNOWN, 0);
    private static final Result OPEN_AI = new Result(Action.OPEN_AI, 0);

    private SignalParser() {
    }

    /**
     * 현재 펌웨어가 보낸 프레임을 해석합니다.
     *
     * 여기서 하는 일은 "번역"뿐입니다. 깨진 프레임을 걸러 내는 일은 {@link FrameCodec.Decoder}가,
     * 같은 누름을 두 번 실행하지 않는 일은 SEQ를 보는 {@link UsbSerialService}가 맡습니다.
     * 이렇게 나눠 두어야 각각을 따로 시험할 수 있습니다.
     */
    public static Result fromFrame(FrameCodec.Frame frame) {
        if (frame == null || frame.type != FrameCodec.T_EVT_PRESS || frame.len() < 2) {
            return UNKNOWN;                       // 버튼 누름이 아닌 프레임(HELLO·STATS 등)
        }
        int button = frame.u8(0);
        int kind = frame.u8(1);
        int latencyUs = frame.len() >= 4 ? frame.u16(2) : -1;

        if (kind == FrameCodec.K_LONG) {
            return new Result(Action.OPEN_AI, 0, frame.seq, kind, latencyUs);
        }
        if (button < 1 || button > 3) {
            return UNKNOWN;                       // 있지도 않은 버튼 번호는 실행하지 않습니다
        }
        return new Result(Action.RUN_SLOT, button, frame.seq, kind, latencyUs);
    }

    /**
     * 받아들이는 신호
     *   짧게 누름 : "True", "Button1", "1"  /  "Button2", "2"  /  "Button3", "3"
     *   길게 누름 : "AI", "Long1", "ButtonLong1"
     *
     * 앞뒤 공백과 캐리지리턴(\r), 대소문자는 무시합니다.
     */
    public static Result parse(String rawLine) {
        if (rawLine == null) {
            return UNKNOWN;
        }
        String signal = rawLine.trim().toLowerCase(Locale.ROOT);
        if (signal.isEmpty()) {
            return UNKNOWN;
        }

        // 길게 누름을 먼저 봅니다. ("buttonlong1" 이 "button1" 로 잘못 읽히지 않도록)
        if (signal.equals("ai") || signal.startsWith("long") || signal.startsWith("buttonlong")) {
            return OPEN_AI;
        }

        switch (signal) {
            case "true":
            case "button1":
            case "1":
                return new Result(Action.RUN_SLOT, 1);
            case "button2":
            case "2":
                return new Result(Action.RUN_SLOT, 2);
            case "button3":
            case "3":
                return new Result(Action.RUN_SLOT, 3);
            default:
                return UNKNOWN;
        }
    }
}
