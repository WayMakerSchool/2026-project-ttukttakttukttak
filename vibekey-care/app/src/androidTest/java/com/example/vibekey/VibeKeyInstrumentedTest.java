package com.example.vibekey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 진짜 휴대폰(또는 에뮬레이터)에서 도는 검사입니다.
 * 저장·바로가기·신호 기록처럼 안드로이드가 있어야 확인되는 부분을 다룹니다.
 *
 * 실행: ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4.class)
public class VibeKeyInstrumentedTest {

    private Context context;
    private Prefs prefs;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs = Prefs.with(context);
        clearAll();
    }

    @After
    public void tearDown() {
        clearAll();
    }

    private void clearAll() {
        SharedPreferences sp =
                context.getSharedPreferences(Prefs.PREFS_NAME, Context.MODE_PRIVATE);
        sp.edit().clear().commit();
        SerialLog.clear();
    }

    @Test
    public void 버튼에_넣은_앱이_저장되고_다시_읽힌다() {
        prefs.setSlot(2, "com.kakao.talk", "카카오톡");

        assertEquals("com.kakao.talk", prefs.getSlotPackage(2));
        assertEquals("카카오톡", prefs.getSlotLabel(2));
        assertTrue(prefs.hasSlot(2));
    }

    @Test
    public void 버튼을_비우면_비워진다() {
        prefs.setSlot(3, "com.kakao.talk", "카카오톡");
        prefs.clearSlot(3);

        assertFalse(prefs.hasSlot(3));
    }

    /**
     * 예전 버전이 저장해 둔 값을 새 버전이 그대로 읽어야 합니다.
     * 이게 깨지면 업데이트한 어르신의 설정이 통째로 사라집니다.
     */
    @Test
    public void 예전_버전이_저장한_값을_그대로_읽는다() {
        SharedPreferences sp =
                context.getSharedPreferences(Prefs.PREFS_NAME, Context.MODE_PRIVATE);
        sp.edit()
                .putString("target_package_name", "com.nhn.android.nmap")
                .putString("target_app_label", "네이버 지도")
                .commit();

        assertEquals("com.nhn.android.nmap", prefs.getSlotPackage(1));
        assertEquals("네이버 지도", prefs.getSlotLabel(1));
    }

    @Test
    public void 설정하지_않은_버튼은_비어_있다() {
        assertFalse(prefs.hasSlot(2));
        assertFalse(prefs.hasSlot(3));
    }

    @Test
    public void 신호_기록이_쌓이고_최근_것이_맨_앞에_온다() {
        SerialLog.add(context, "Button1", "1번 버튼 실행");
        SerialLog.add(context, "Button2", "2번 버튼 실행");

        assertEquals(2, SerialLog.snapshot().size());
        assertTrue(SerialLog.snapshot().get(0).contains("Button2"));

        SerialLog.clear();
        assertTrue(SerialLog.isEmpty());
    }

    @Test
    public void 삼성_루틴_바로가기가_등록된다() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            return; // 안드로이드 7.1 미만은 동적 바로가기를 지원하지 않습니다.
        }
        prefs.setSlot(1, "com.kakao.talk", "카카오톡");
        RoutineBridge.refreshShortcuts(context);

        assertFalse(ShortcutManagerCompat.getDynamicShortcuts(context).isEmpty());
    }

    @Test
    public void 기본값은_말소리_안내와_진동이_켜져_있다() {
        // 어르신 기준 기본값이라 실수로 바뀌면 안 됩니다.
        assertTrue(prefs.isVoiceFeedbackOn());
        assertTrue(prefs.isHapticOn());
        assertFalse(prefs.isBiggerTextOn());
    }
}
