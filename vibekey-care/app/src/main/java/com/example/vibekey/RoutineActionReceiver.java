package com.example.vibekey;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;

/**
 * 삼성 루틴·태스커·매크로드로이드 같은 자동화 앱이 브로드캐스트로 바이브키를 시킬 때 받는 곳입니다.
 *
 * 예) adb shell am broadcast -a com.example.vibekey.action.RUN_BUTTON --ei button 1 \
 *         -n com.example.vibekey/.RoutineActionReceiver
 */
public class RoutineActionReceiver extends BroadcastReceiver {

    private static final String TAG = "VibeKeyRoutineRcv";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        Log.d(TAG, "routine request: " + action);

        switch (action) {
            case RoutineBridge.ACTION_RUN_BUTTON: {
                int slot = intent.getIntExtra(RoutineBridge.EXTRA_BUTTON, 0);
                if (slot < 1 || slot > Prefs.SLOT_COUNT) {
                    // 문자열로 넘어오는 경우도 받아 줍니다.
                    slot = RoutineBridge.parseSlotFromText(
                            intent.getStringExtra(RoutineBridge.EXTRA_BUTTON));
                }
                if (slot >= 1 && slot <= Prefs.SLOT_COUNT) {
                    AppLauncher.runSlot(context, slot, "routine");
                } else {
                    Log.w(TAG, "RUN_BUTTON without a valid 'button' extra");
                }
                break;
            }
            case RoutineBridge.ACTION_SPEAK: {
                String text = intent.getStringExtra(RoutineBridge.EXTRA_TEXT);
                if (!TextUtils.isEmpty(text)) {
                    SpeechManager.get(context).speak(text);
                }
                break;
            }
            case RoutineBridge.ACTION_OPEN_AI:
            case RoutineBridge.ACTION_ASK_AI: {
                Intent forward = new Intent(context, RoutineActionActivity.class);
                forward.setAction(RoutineBridge.ACTION_OPEN_AI);
                forward.putExtra(RoutineBridge.EXTRA_TEXT,
                        intent.getStringExtra(RoutineBridge.EXTRA_TEXT));
                forward.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(forward);
                break;
            }
            default:
                Log.w(TAG, "Unhandled action: " + action);
        }
    }
}
