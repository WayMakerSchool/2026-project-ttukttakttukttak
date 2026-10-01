package com.example.vibekey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * AI를 쓸 수 없을 때(키 없음·인터넷 끊김) 동작하는 오프라인 낱말 사전을 검사합니다.
 * 어르신이 실제로 하실 법한 말투로 검사합니다.
 */
public class KeywordMatcherTest {

    @Test
    public void 길_찾고_싶어_라고_하면_지도_앱을_고른다() {
        List<String> candidates = KeywordMatcher.candidatesFor("길 찾고 싶어");
        assertFalse(candidates.isEmpty());
        assertTrue(candidates.contains("com.nhn.android.nmap")
                || candidates.contains("com.google.android.apps.maps"));
    }

    @Test
    public void 아들한테_전화_라고_하면_전화_앱을_고른다() {
        List<String> candidates = KeywordMatcher.candidatesFor("아들한테 전화 걸고 싶어");
        assertTrue(candidates.contains("com.samsung.android.dialer")
                || candidates.contains("com.google.android.dialer"));
    }

    @Test
    public void 약_먹을_시간_알려줘_라고_하면_시계_앱을_고른다() {
        List<String> candidates = KeywordMatcher.candidatesFor("약 먹을 시간 알려줘");
        assertTrue(candidates.contains("com.google.android.deskclock")
                || candidates.contains("com.sec.android.app.clockpackage"));
    }

    @Test
    public void 후보는_사전에_적힌_우선순위대로_나온다() {
        List<String> candidates = KeywordMatcher.candidatesFor("지도");
        assertEquals("com.nhn.android.nmap", candidates.get(0));
    }

    @Test
    public void 같은_앱이_두_번_나오지_않는다() {
        // "길"과 "지도" 둘 다 걸리는 문장 - 후보가 겹쳐도 한 번만 나와야 합니다.
        List<String> candidates = KeywordMatcher.candidatesFor("길 찾게 지도 좀 켜줘");
        for (String candidate : candidates) {
            assertEquals(1, java.util.Collections.frequency(candidates, candidate));
        }
    }

    @Test
    public void 짚이는_낱말이_없으면_빈_목록() {
        assertTrue(KeywordMatcher.candidatesFor("음뭐라고할까").isEmpty());
        assertTrue(KeywordMatcher.candidatesFor("").isEmpty());
        assertTrue(KeywordMatcher.candidatesFor("   ").isEmpty());
        assertTrue(KeywordMatcher.candidatesFor(null).isEmpty());
    }

    @Test
    public void 사전이_비어_있지_않다() {
        assertTrue(KeywordMatcher.keywordCount() > 20);
    }
}
