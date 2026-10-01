package com.example.vibekey;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * 눌렀다는 사실을 손끝으로도 알 수 있게 진동을 줍니다. (설정에서 끌 수 있습니다.)
 *
 * <h3>기기에서 일어난 일을 폰의 진동으로 옮깁니다</h3>
 * 케이스에는 진동 장치가 없습니다(버튼 3개 + USB가 전부). 그런데 진동 장치는
 * 이미 사용자 손에 있습니다 — <b>폰 안에</b>. 그래서 기기가 보낸 프레임의 내용
 * (몇 번 버튼인지 · 짧게인지 길게인지 · 앱이 열렸는지)에 따라 <b>서로 다른 진동 패턴</b>을
 * 폰이 울립니다. 부품을 하나도 안 늘리고 "화면을 보지 않아도 안다"를 만드는 방법입니다.
 *
 * <pre>
 *   1번 버튼   ·        (짧게 한 번)
 *   2번 버튼   · ·      (짧게 두 번)
 *   3번 버튼   —        (길게 한 번)
 *   길게 누름  · —      (짧게-길게, AI 도우미)
 *   성공       · ·      (산뜻하게 두 번)
 *   실패       — — —    (무겁게 세 번)
 * </pre>
 *
 * 패턴이 버튼마다 다르므로 주머니 안에서도 <b>몇 번 버튼이 눌렸는지</b> 구분됩니다.
 */
public final class Haptics {

    private Haptics() {
    }

    public static void tap(Context context) {
        vibrate(context, 30);
    }

    public static void success(Context context) {
        pattern(context, new long[]{0, 40, 60, 40});
    }

    /** 앱을 못 열었을 때. 성공과 확실히 구분되도록 무겁고 길게 세 번. */
    public static void failure(Context context) {
        pattern(context, new long[]{0, 200, 120, 200, 120, 200});
    }

    /**
     * 기기 버튼이 눌린 것을 알립니다. 버튼마다 패턴이 다릅니다.
     *
     * @param slot 1~3 (길게 누름이면 0)
     * @param longPress 길게 누름인가 (AI 도우미)
     */
    public static void buttonPressed(Context context, int slot, boolean longPress) {
        if (longPress) {
            pattern(context, new long[]{0, 50, 70, 220});     // 짧게-길게
            return;
        }
        switch (slot) {
            case 2:
                pattern(context, new long[]{0, 55, 90, 55});  // 톡톡
                break;
            case 3:
                pattern(context, new long[]{0, 220});         // 우웅
                break;
            default:
                pattern(context, new long[]{0, 55});          // 톡
                break;
        }
    }

    /**
     * 기기가 보낸 신호를 폰이 세 번이나 못 받아 준 경우 등, 문제가 생겼을 때.
     * 성공·실패 어느 쪽과도 헷갈리지 않도록 아주 길게 한 번 울립니다.
     */
    public static void alert(Context context) {
        pattern(context, new long[]{0, 500});
    }

    /** (쉬는 시간, 울리는 시간, 쉬는 시간, …) 배열을 그대로 재생합니다. */
    private static void pattern(Context context, long[] timings) {
        if (context == null || !Prefs.with(context).isHapticOn()) {
            return;
        }
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(timings, -1));
        } else {
            //noinspection deprecation
            vibrator.vibrate(timings, -1);
        }
    }

    private static void vibrate(Context context, long millis) {
        if (context == null || !Prefs.with(context).isHapticOn()) {
            return;
        }
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            //noinspection deprecation
            vibrator.vibrate(millis);
        }
    }
}
