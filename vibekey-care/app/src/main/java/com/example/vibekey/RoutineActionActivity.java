package com.example.vibekey;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

/**
 * 삼성 루틴(모드 및 루틴)이나 다른 자동화 앱이 바이브키를 실행시킬 때 사용하는
 * "화면 없는" 액티비티입니다. 시킨 일만 하고 곧바로 사라집니다.
 *
 * 지원하는 호출 방법
 *   · 앱 바로가기 : 루틴 → 앱 실행 → 바이브키 → "1번 버튼" 바로가기
 *   · 딥링크      : vibekey://run/1 , vibekey://ai , vibekey://speak?text=약 드실 시간이에요
 *   · 인텐트      : com.example.vibekey.action.RUN_BUTTON (extra: button)
 */
public class RoutineActionActivity extends AppCompatActivity {

    private static final String TAG = "VibeKeyRoutineAct";

    /** 매니페스트의 activity-alias 이름. RoutineBridge와 짝을 맞춰야 합니다. */
    private static final String ALIAS_BUTTON_PREFIX = ".RunButton";
    private static final String ALIAS_AI = ".RunAi";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handle(getIntent());
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handle(intent);
        finish();
    }

    private void handle(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        Uri data = intent.getData();

        // 1) 딥링크로 들어온 경우
        if (data != null && RoutineBridge.SCHEME.equals(data.getScheme())) {
            String host = data.getHost();
            if ("ai".equals(host)) {
                openAssistant(data.getQueryParameter("q"));
                return;
            }
            if ("speak".equals(host)) {
                String text = data.getQueryParameter("text");
                if (!TextUtils.isEmpty(text)) {
                    SpeechManager.get(this).speak(text);
                }
                return;
            }
            if ("run".equals(host)) {
                int slot = slotFromPath(data);
                if (slot > 0) {
                    AppLauncher.runSlot(this, slot, "routine");
                    return;
                }
            }
        }

        // 2) 인텐트 액션으로 들어온 경우
        if (RoutineBridge.ACTION_OPEN_AI.equals(action)) {
            openAssistant(intent.getStringExtra(RoutineBridge.EXTRA_TEXT));
            return;
        }
        if (RoutineBridge.ACTION_ASK_AI.equals(action)) {
            openAssistant(intent.getStringExtra(RoutineBridge.EXTRA_TEXT));
            return;
        }
        if (RoutineBridge.ACTION_SPEAK.equals(action)) {
            String text = intent.getStringExtra(RoutineBridge.EXTRA_TEXT);
            if (!TextUtils.isEmpty(text)) {
                SpeechManager.get(this).speak(text);
            }
            return;
        }
        if (RoutineBridge.ACTION_RUN_BUTTON.equals(action)) {
            int slot = intent.getIntExtra(RoutineBridge.EXTRA_BUTTON, 0);
            if (slot >= 1 && slot <= Prefs.SLOT_COUNT) {
                AppLauncher.runSlot(this, slot, "routine");
                return;
            }
        }

        // 3) 런처 실행 항목(별칭)으로 들어온 경우
        //    루틴 앱이 "바이브키 1번 버튼"을 앱처럼 실행하면 액션도 데이터도 없이
        //    들어오므로, 어떤 별칭이 불렸는지로 판단합니다.
        int aliasSlot = slotFromComponent(intent);
        if (aliasSlot > 0) {
            AppLauncher.runSlot(this, aliasSlot, "routine");
            return;
        }
        if (isAiAlias(intent)) {
            openAssistant(null);
            return;
        }

        Log.w(TAG, "Unknown routine request: action=" + action + " data=" + data);
    }

    /**
     * 매니페스트의 실행 항목 별칭 이름에서 버튼 번호를 읽어 냅니다.
     * ".RunButton2" 로 들어왔으면 2를 돌려 줍니다. 별칭이 아니면 0입니다.
     */
    private int slotFromComponent(Intent intent) {
        String name = aliasName(intent);
        if (name == null || !name.startsWith(ALIAS_BUTTON_PREFIX)) {
            return 0;
        }
        return RoutineBridge.parseSlotFromText(name.substring(ALIAS_BUTTON_PREFIX.length()));
    }

    private boolean isAiAlias(Intent intent) {
        return ALIAS_AI.equals(aliasName(intent));
    }

    private String aliasName(Intent intent) {
        ComponentName component = intent.getComponent();
        return component == null ? null : component.getShortClassName();
    }

    private void openAssistant(String question) {
        Intent aiIntent = new Intent(this, AiAssistantActivity.class);
        aiIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (!TextUtils.isEmpty(question)) {
            aiIntent.putExtra(AiAssistantActivity.EXTRA_QUESTION, question);
        } else {
            aiIntent.putExtra(AiAssistantActivity.EXTRA_AUTO_LISTEN, true);
        }
        startActivity(aiIntent);
    }

    /** vibekey://run/2 또는 vibekey://run?button=2 두 형태를 모두 받아들입니다. */
    private int slotFromPath(Uri data) {
        List<String> segments = data.getPathSegments();
        if (segments != null && !segments.isEmpty()) {
            int slot = RoutineBridge.parseSlotFromText(segments.get(0));
            if (slot > 0) {
                return slot;
            }
        }
        return RoutineBridge.parseSlotFromText(data.getQueryParameter(RoutineBridge.EXTRA_BUTTON));
    }
}
