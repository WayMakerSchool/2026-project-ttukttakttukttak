package com.example.vibekey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 조사 고르기를 검사합니다.
 *
 * 이 앱은 화면 글을 소리로도 읽어 드리기 때문에, 여기가 틀리면
 * 어르신 귀에 "카카오톡을 괄호 를 괄호 엽니다" 처럼 들립니다.
 */
public class KoreanParticleTest {

    @Test
    public void 받침이_있으면_을_이_은() {
        assertEquals("을", KoreanParticle.eulReul("카카오톡"));
        assertEquals("이", KoreanParticle.iGa("카카오톡"));
        assertEquals("은", KoreanParticle.eunNeun("카카오톡"));
    }

    @Test
    public void 받침이_없으면_를_가_는() {
        assertEquals("를", KoreanParticle.eulReul("지도"));
        assertEquals("가", KoreanParticle.iGa("지도"));
        assertEquals("는", KoreanParticle.eunNeun("지도"));
    }

    @Test
    public void 앱_이름에_붙여_준다() {
        assertEquals("카카오톡을", KoreanParticle.withEulReul("카카오톡"));
        assertEquals("네이버 지도를", KoreanParticle.withEulReul("네이버 지도"));
        assertEquals("전화 걸기를", KoreanParticle.withEulReul("전화 걸기"));
        assertEquals("길 찾기를", KoreanParticle.withEulReul("길 찾기"));
        assertEquals("사진 보기를", KoreanParticle.withEulReul("사진 보기"));
        assertEquals("알림을", KoreanParticle.withEulReul("알림"));
    }

    @Test
    public void 으로와_로를_가려_쓴다() {
        assertEquals("으로", KoreanParticle.euroRo("병원"));
        assertEquals("로", KoreanParticle.euroRo("지도"));
        // 'ㄹ' 받침은 예외라 "서울로" 가 맞습니다.
        assertEquals("로", KoreanParticle.euroRo("서울"));
    }

    @Test
    public void 영어_이름도_읽는_소리로_가린다() {
        // 끝의 e 는 소리가 없으니 Phone 은 'ㄴ' 으로 끝난다고 봅니다.
        assertEquals("폰", "폰");
        assertEquals("을", KoreanParticle.eulReul("Phone"));
        assertEquals("을", KoreanParticle.eulReul("Chrome"));
        assertEquals("을", KoreanParticle.eulReul("Gmail"));
        // 스·브 처럼 받침 없이 끝나는 소리
        assertEquals("를", KoreanParticle.eulReul("Maps"));
        assertEquals("를", KoreanParticle.eulReul("YouTube"));
        assertEquals("를", KoreanParticle.eulReul("Toss"));
    }

    @Test
    public void 숫자로_끝나도_읽는_소리로_가린다() {
        assertEquals("을", KoreanParticle.eulReul("버튼 1"));  // 일
        assertEquals("를", KoreanParticle.eulReul("버튼 2"));  // 이
        assertEquals("을", KoreanParticle.eulReul("버튼 3"));  // 삼
        assertEquals("를", KoreanParticle.eulReul("버튼 4"));  // 사
        assertEquals("을", KoreanParticle.eulReul("버튼 7"));  // 칠
        assertEquals("를", KoreanParticle.eulReul("버튼 9"));  // 구
    }

    @Test
    public void 따옴표나_괄호로_끝나도_속의_글자로_가린다() {
        assertEquals("을", KoreanParticle.eulReul("'카카오톡'"));
        assertEquals("를", KoreanParticle.eulReul("(지도)"));
        assertEquals("을", KoreanParticle.eulReul("카카오톡."));
    }

    @Test
    public void 빈_글자도_터지지_않는다() {
        assertEquals("를", KoreanParticle.eulReul(null));
        assertEquals("를", KoreanParticle.eulReul(""));
        assertEquals("를", KoreanParticle.eulReul("   "));
        assertEquals("를", KoreanParticle.eulReul("!!!"));
    }

    @Test
    public void 받침_판정을_직접_확인한다() {
        assertTrue(KoreanParticle.hasFinalConsonant("톡"));
        assertTrue(KoreanParticle.hasFinalConsonant("길"));
        assertFalse(KoreanParticle.hasFinalConsonant("도"));
        assertFalse(KoreanParticle.hasFinalConsonant("기"));
    }
}
