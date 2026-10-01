package com.example.vibekey;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 삼성 루틴·자동화 앱이 여러 모양으로 보내 오는 버튼 번호를 제대로 읽는지 검사합니다.
 * (딥링크 vibekey://run/2, 쿼리 ?button=3, 문자열 extra "1" 등)
 */
public class RoutineSlotParseTest {

    @Test
    public void 숫자만_들어와도_읽는다() {
        assertEquals(1, RoutineBridge.parseSlotFromText("1"));
        assertEquals(2, RoutineBridge.parseSlotFromText("2"));
        assertEquals(3, RoutineBridge.parseSlotFromText("3"));
    }

    @Test
    public void 앞뒤_공백을_무시한다() {
        assertEquals(2, RoutineBridge.parseSlotFromText("  2  "));
    }

    @Test
    public void button_이퀄_형태를_읽는다() {
        assertEquals(3, RoutineBridge.parseSlotFromText("button=3"));
        assertEquals(1, RoutineBridge.parseSlotFromText("vibekey://run?button=1"));
    }

    /** 없는 버튼 번호를 그대로 실행하면 엉뚱한 동작이 되므로 0(무시)이어야 합니다. */
    @Test
    public void 범위를_벗어난_번호는_무시한다() {
        assertEquals(0, RoutineBridge.parseSlotFromText("0"));
        assertEquals(0, RoutineBridge.parseSlotFromText("4"));
        assertEquals(0, RoutineBridge.parseSlotFromText("99"));
    }

    @Test
    public void 읽을_수_없으면_무시한다() {
        assertEquals(0, RoutineBridge.parseSlotFromText("abc"));
        assertEquals(0, RoutineBridge.parseSlotFromText(""));
        assertEquals(0, RoutineBridge.parseSlotFromText(null));
    }
}
