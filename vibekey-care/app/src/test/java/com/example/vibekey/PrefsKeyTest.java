package com.example.vibekey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/**
 * 저장 키가 예전 버전과 같은지 검사합니다.
 * 이 키가 바뀌면 사용자가 예전에 지정해 둔 앱이 업데이트 후 모두 사라집니다.
 */
public class PrefsKeyTest {

    @Test
    public void 첫째_버튼은_예전_키를_그대로_쓴다() {
        assertEquals("target_package_name", Prefs.packageKey(1));
        assertEquals("target_app_label", Prefs.labelKey(1));
    }

    @Test
    public void 둘째_셋째_버튼은_번호가_붙은_키를_쓴다() {
        assertEquals("target_package_name_2", Prefs.packageKey(2));
        assertEquals("target_app_label_2", Prefs.labelKey(2));
        assertEquals("target_package_name_3", Prefs.packageKey(3));
        assertEquals("target_app_label_3", Prefs.labelKey(3));
    }

    @Test
    public void 버튼마다_키가_겹치지_않는다() {
        assertNotEquals(Prefs.packageKey(1), Prefs.packageKey(2));
        assertNotEquals(Prefs.packageKey(2), Prefs.packageKey(3));
        assertNotEquals(Prefs.packageKey(1), Prefs.labelKey(1));
    }

    @Test
    public void 버튼은_세_개다() {
        assertEquals(3, Prefs.SLOT_COUNT);
    }
}
