package com.example.vibekey;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

/**
 * 버튼(하드웨어 버튼 · 삼성 루틴 · 화면의 "눌러 보기")에서 공통으로 쓰는 앱 실행 도우미입니다.
 * 실행 결과를 소리와 진동으로도 알려 주어, 화면을 못 보셔도 알 수 있게 합니다.
 */
public final class AppLauncher {

    private static final String TAG = "VibeKeyLauncher";

    private AppLauncher() {
    }

    /**
     * 지정한 버튼 번호에 연결된 앱을 실행합니다.
     *
     * <p><b>잠겨 있으면 먼저 잠금을 풉니다.</b> 이 기기는 목에 걸거나 주머니에 넣고
     * 다니므로 단추를 누르는 순간 폰은 거의 항상 잠겨 있습니다. 그런데 우리가 여는
     * 앱(전화·지도·사진)은 남의 앱이라 "잠금화면 위에 보이기"를 선언해 두지 않았습니다.
     * 그냥 열면 <b>잠금화면 뒤에서</b> 열려, 어르신 눈에는 아무 일도 안 일어난 것과 같습니다.
     * 그래서 잠겨 있을 때는 {@link LaunchGateActivity} 를 거쳐 잠금을 푼 뒤에 엽니다.
     *
     * @param source 어디서 눌렀는지 (기록/루틴 알림용): "hardware", "routine", "screen"
     * @return 실제로 앱을 열었으면 true (잠금 해제를 기다리는 중이면 true)
     */
    public static boolean runSlot(Context context, int slot, String source) {
        if (isKeyguardLocked(context)) {
            LaunchGateActivity.open(context, slot, source);
            return true;    // 실제 실행은 잠금이 풀린 뒤 문지기가 합니다
        }
        return runSlotNow(context, slot, source);
    }

    /** 지금 화면이 잠겨 있는지. 알 수 없으면 잠기지 않은 것으로 봅니다. */
    private static boolean isKeyguardLocked(Context context) {
        KeyguardManager keyguard =
                (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return keyguard != null && keyguard.isKeyguardLocked();
    }

    /**
     * 잠금 처리를 건너뛰고 지금 바로 실행합니다.
     * {@link LaunchGateActivity} 가 잠금을 푼 뒤에 부릅니다 — 여기서 다시 runSlot 을
     * 부르면 문지기가 또 열려 무한히 겹칩니다.
     */
    static boolean runSlotNow(Context context, int slot, String source) {
        Prefs prefs = Prefs.with(context);
        String type = prefs.getSlotActionType(slot);
        String label = prefs.getSlotLabel(slot);

        if (!"APP".equals(type)) {
            return runQuickAction(context, slot, type, label, source);
        }

        String packageName = prefs.getSlotPackage(slot);
        if (TextUtils.isEmpty(packageName)) {
            String message = slot + "번 버튼에 연결된 앱이 아직 없어요.";
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            SpeechManager.get(context).speakIfEnabled(context, message);
            return false;
        }

        boolean launched = launchPackage(context, packageName);
        if (launched) {
            Haptics.success(context);
            String spoken = TextUtils.isEmpty(label) ? "앱을 엽니다." : KoreanParticle.withEulReul(label) + " 엽니다.";
            SpeechManager.get(context).speakIfEnabled(context, spoken);
            RoutineBridge.notifyButtonPressed(context, slot, packageName, label, source);
        } else {
            String missing = TextUtils.isEmpty(label) ? "그 앱" : label;
            String message = missing + KoreanParticle.iGa(missing) + " 휴대폰에 없어요. 다시 정해 주세요.";
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            SpeechManager.get(context).speakIfEnabled(context, message);
        }
        return launched;
    }

    /** 앱을 여는 대신 전화 걸기·문자 보내기·길찾기처럼 정해 둔 동작을 바로 실행합니다. */
    private static boolean runQuickAction(Context context, int slot, String type, String label, String source) {
        Prefs prefs = Prefs.with(context);
        String number = prefs.getSlotActionNumber(slot);
        String text = prefs.getSlotActionText(slot);
        String labelOrDefault = TextUtils.isEmpty(label) ? "이 동작" : label;

        boolean launched;
        String successSpoken;
        switch (type) {
            case "DIAL":
                launched = IntentActions.dial(context, number);
                successSpoken = labelOrDefault + "으로 전화 걸 준비를 했어요. 통화 버튼을 눌러 주세요.";
                break;
            case "SMS":
                launched = IntentActions.sms(context, number, text);
                successSpoken = labelOrDefault + " 문자를 준비했어요. 보내기 버튼을 눌러 주세요.";
                break;
            case "MAPS":
                launched = IntentActions.navigate(context, text);
                successSpoken = labelOrDefault + "로 가는 길을 찾아 드릴게요.";
                break;
            default:
                launched = false;
                successSpoken = "";
                break;
        }

        if (launched) {
            Haptics.success(context);
            SpeechManager.get(context).speakIfEnabled(context, successSpoken);
            RoutineBridge.notifyButtonPressed(context, slot, "", label, source);
        } else {
            String message = labelOrDefault + " 동작을 실행하지 못했어요. 다시 정해 주세요.";
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            SpeechManager.get(context).speakIfEnabled(context, message);
        }
        return launched;
    }

    /** 패키지명으로 앱을 실행합니다. 설치돼 있지 않으면 false 를 돌려 줍니다. */
    public static boolean launchPackage(Context context, String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return false;
        }
        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launchIntent == null) {
            Log.w(TAG, "Target app not installed: " + packageName);
            return false;
        }
        // 서비스·리시버(=백그라운드)에서도 화면을 띄울 수 있도록 필수 플래그를 붙입니다.
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            context.startActivity(launchIntent);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch " + packageName, e);
            return false;
        }
    }
}
