package com.example.vibekey;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 삼성 "모드 및 루틴"(Modes and Routines) 연동을 담당하는 다리 역할 클래스입니다.
 *
 * 두 방향을 모두 지원합니다.
 *
 * [1] 루틴 → 바이브키 (루틴이 우리 앱을 시킬 때)
 *     · 브로드캐스트 : com.example.vibekey.action.RUN_BUTTON (extra: button = 1~3)
 *     · 딥링크       : vibekey://run/1 , vibekey://ai , vibekey://speak?text=...
 *     · 앱 바로가기  : 루틴의 "앱 실행 → 바로가기"에서 바로 고를 수 있도록 동적 바로가기를 등록
 *
 * [2] 바이브키 → 루틴 (우리 앱에서 생긴 일을 루틴에 알릴 때)
 *     · 브로드캐스트 : com.example.vibekey.event.BUTTON_PRESSED / DEVICE_STATE
 *       (삼성 루틴·SmartThings·태스커 등에서 조건으로 받아 쓸 수 있습니다.)
 */
public final class RoutineBridge {

    private static final String TAG = "VibeKeyRoutine";

    // ---------------------------------------------------------------- 들어오는 명령
    public static final String ACTION_RUN_BUTTON = "com.example.vibekey.action.RUN_BUTTON";
    public static final String ACTION_OPEN_AI = "com.example.vibekey.action.OPEN_AI";
    public static final String ACTION_SPEAK = "com.example.vibekey.action.SPEAK";
    public static final String ACTION_ASK_AI = "com.example.vibekey.action.ASK_AI";

    // ---------------------------------------------------------------- 나가는 알림
    public static final String EVENT_BUTTON_PRESSED = "com.example.vibekey.event.BUTTON_PRESSED";
    public static final String EVENT_DEVICE_STATE = "com.example.vibekey.event.DEVICE_STATE";

    public static final String EXTRA_BUTTON = "button";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_PACKAGE = "package";
    public static final String EXTRA_LABEL = "label";
    public static final String EXTRA_SOURCE = "source";
    public static final String EXTRA_CONNECTED = "connected";

    public static final String SCHEME = "vibekey";

    private RoutineBridge() {
    }

    // ---------------------------------------------------------------- 나가는 알림 보내기

    /** 버튼이 눌려 앱이 열렸다는 사실을 바깥(삼성 루틴 등)에 알립니다. */
    public static void notifyButtonPressed(Context context, int slot, String packageName,
                                           String label, String source) {
        if (!Prefs.with(context).isRoutineBroadcastOn()) {
            return;
        }
        Intent intent = new Intent(EVENT_BUTTON_PRESSED);
        intent.putExtra(EXTRA_BUTTON, slot);
        intent.putExtra(EXTRA_PACKAGE, packageName);
        intent.putExtra(EXTRA_LABEL, label);
        intent.putExtra(EXTRA_SOURCE, source == null ? "unknown" : source);
        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        context.sendBroadcast(intent);
        Log.d(TAG, "broadcast BUTTON_PRESSED slot=" + slot + " source=" + source);
    }

    /** 기기가 꽂히거나 빠졌다는 사실을 바깥에 알립니다. */
    public static void notifyDeviceState(Context context, boolean connected) {
        if (!Prefs.with(context).isRoutineBroadcastOn()) {
            return;
        }
        Intent intent = new Intent(EVENT_DEVICE_STATE);
        intent.putExtra(EXTRA_CONNECTED, connected);
        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        context.sendBroadcast(intent);
    }

    // ---------------------------------------------------------------- 딥링크

    public static Uri runButtonUri(int slot) {
        return Uri.parse(SCHEME + "://run/" + slot);
    }

    /**
     * "2", " 3 ", "button=1" 처럼 여러 모양으로 오는 버튼 번호를 읽어 냅니다.
     * 1~3 범위를 벗어나거나 읽을 수 없으면 0을 돌려 줍니다.
     *
     * 안드로이드 클래스를 쓰지 않아 단위 테스트로 바로 검증할 수 있습니다.
     * (app/src/test/java/com/example/vibekey/RoutineSlotParseTest.java)
     */
    public static int parseSlotFromText(String raw) {
        if (raw == null) {
            return 0;
        }
        String text = raw.trim();
        int marker = text.indexOf(EXTRA_BUTTON + "=");
        if (marker >= 0) {
            text = text.substring(marker + EXTRA_BUTTON.length() + 1).trim();
        }

        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < text.length() && Character.isDigit(text.charAt(i)); i++) {
            digits.append(text.charAt(i));
        }
        if (digits.length() == 0) {
            return 0;
        }
        int slot = Integer.parseInt(digits.toString());
        return (slot >= 1 && slot <= Prefs.SLOT_COUNT) ? slot : 0;
    }

    public static Uri openAiUri() {
        return Uri.parse(SCHEME + "://ai");
    }

    /** 루틴 앱에서 복사해 쓸 수 있도록 사람이 읽을 수 있는 안내 문구를 만듭니다. */
    public static String buildHowToText(Context context) {
        Prefs prefs = Prefs.with(context);
        StringBuilder sb = new StringBuilder();
        sb.append("[삼성 루틴에서 바이브키 실행하기]\n\n");
        sb.append("방법 1 · 가장 쉬움 (버튼별 실행 항목)\n");
        sb.append("  먼저 이 화면의 '바로가기 만들기'를 한 번 눌러 주세요.\n");
        sb.append("  루틴 만들기 → 실행할 동작 → '앱 실행' →\n");
        sb.append("  목록에서 '바이브키 1번 버튼'을 고르면 됩니다.\n");
        sb.append("  (앱 이름처럼 버튼마다 따로 나옵니다.)\n\n");
        sb.append("방법 2 · 자동화 앱(태스커·매크로드로이드)에서\n");
        sb.append("  보낼 브로드캐스트: ").append(ACTION_RUN_BUTTON).append('\n');
        sb.append("  추가 값(정수): button = 1 / 2 / 3\n\n");
        sb.append("방법 3 · 주소(딥링크)로\n");
        for (int slot = 1; slot <= Prefs.SLOT_COUNT; slot++) {
            String label = prefs.getSlotLabel(slot);
            sb.append("  ").append(runButtonUri(slot));
            if (!TextUtils.isEmpty(label)) {
                sb.append("   → ").append(label);
            }
            sb.append('\n');
        }
        sb.append("  ").append(openAiUri()).append("   → AI 도우미 열기\n\n");
        sb.append("[바이브키가 루틴에 알려 주는 신호]\n");
        sb.append("  ").append(EVENT_BUTTON_PRESSED).append("  (button, package, label)\n");
        sb.append("  ").append(EVENT_DEVICE_STATE).append("  (connected: true/false)\n");
        return sb.toString();
    }

    // ---------------------------------------------------------------- 앱 바로가기 등록

    /**
     * 삼성 루틴의 "앱 실행 → 바로가기" 목록에 뜨도록 동적 바로가기를 등록합니다.
     * 연결된 앱 이름이 바뀌면 바로가기 이름도 함께 바뀝니다.
     */
    public static void refreshShortcuts(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            return; // 동적 바로가기는 안드로이드 7.1 이상에서만 지원됩니다.
        }
        try {
            Prefs prefs = Prefs.with(context);
            List<ShortcutInfoCompat> shortcuts = new ArrayList<>();

            for (int slot = 1; slot <= Prefs.SLOT_COUNT; slot++) {
                String label = prefs.getSlotLabel(slot);
                String shortLabel = slot + "번 버튼";
                String longLabel = TextUtils.isEmpty(label)
                        ? slot + "번 버튼 실행"
                        : slot + "번 버튼 · " + label;

                Intent intent = new Intent(context, RoutineActionActivity.class);
                intent.setAction(ACTION_RUN_BUTTON);
                intent.setData(runButtonUri(slot));
                intent.putExtra(EXTRA_BUTTON, slot);
                intent.putExtra(EXTRA_SOURCE, "shortcut");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                shortcuts.add(new ShortcutInfoCompat.Builder(context, "vk_slot_" + slot)
                        .setShortLabel(shortLabel)
                        .setLongLabel(longLabel)
                        .setIcon(IconCompat.createWithResource(context, slotIcon(slot)))
                        .setIntent(intent)
                        .build());
            }

            Intent aiIntent = new Intent(context, RoutineActionActivity.class);
            aiIntent.setAction(ACTION_OPEN_AI);
            aiIntent.setData(openAiUri());
            aiIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            shortcuts.add(new ShortcutInfoCompat.Builder(context, "vk_ai")
                    .setShortLabel("AI 도우미")
                    .setLongLabel("AI 도우미에게 물어보기")
                    .setIcon(IconCompat.createWithResource(context, R.drawable.ic_sparkle))
                    .setIntent(aiIntent)
                    .build());

            ShortcutManagerCompat.removeAllDynamicShortcuts(context);
            ShortcutManagerCompat.addDynamicShortcuts(context, shortcuts);
        } catch (Exception e) {
            // 바로가기 등록 실패가 앱 사용을 막아서는 안 됩니다.
            Log.w(TAG, "Failed to refresh shortcuts", e);
        }
    }

    // ---------------------------------------------------------------- 루틴용 실행 항목

    /**
     * 매니페스트에 선언해 둔 activity-alias 이름들입니다.
     * RoutineActionActivity가 같은 이름으로 버튼 번호를 되읽습니다.
     */
    private static final String[] LAUNCHER_ALIASES = {
            ".RunButton1", ".RunButton2", ".RunButton3", ".RunAi"
    };

    /**
     * 삼성 루틴의 "앱 실행" 목록에 버튼별 항목이 뜨도록 켜거나 끕니다.
     *
     * 루틴 앱은 동적 바로가기를 읽지 않고 런처에 등록된 실행 항목만 보여 주기 때문에,
     * 바로가기만으로는 "1번 버튼"을 고를 수 없습니다. 켜면 앱 서랍에도 항목이
     * 함께 생기므로 기본값은 꺼짐이고, 사용자가 루틴 설정 화면에서 켭니다.
     *
     * @return 실제로 켜고 끄기에 성공했는지
     */
    public static boolean setLauncherEntriesEnabled(Context context, boolean enabled) {
        int state = enabled
                ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        try {
            PackageManager pm = context.getPackageManager();
            String packageName = context.getPackageName();
            for (String alias : LAUNCHER_ALIASES) {
                pm.setComponentEnabledSetting(
                        new ComponentName(packageName, packageName + alias),
                        state,
                        PackageManager.DONT_KILL_APP);
            }
            Log.d(TAG, "launcher entries enabled=" + enabled);
            return true;
        } catch (Exception e) {
            // 켜기에 실패해도 바로가기·딥링크 경로는 그대로 쓸 수 있어야 합니다.
            Log.w(TAG, "Failed to toggle launcher entries", e);
            return false;
        }
    }

    /** 루틴용 실행 항목이 지금 켜져 있는지 확인합니다. */
    public static boolean areLauncherEntriesEnabled(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            String packageName = context.getPackageName();
            int state = pm.getComponentEnabledSetting(
                    new ComponentName(packageName, packageName + LAUNCHER_ALIASES[0]));
            return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        } catch (Exception e) {
            return false;
        }
    }

    private static int slotIcon(int slot) {
        switch (slot) {
            case 2:
                return R.drawable.ic_apps;
            case 3:
                return R.drawable.ic_link;
            default:
                return R.drawable.ic_play;
        }
    }
}
