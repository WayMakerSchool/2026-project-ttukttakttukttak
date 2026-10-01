package com.example.vibekey;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Locale;

/**
 * 모든 화면이 함께 쓰는 기본 액티비티입니다.
 *
 * · 설정의 "글씨 더 크게"를 켜면 화면 전체 글자를 1.3배로 키웁니다.
 * · 어느 화면에서든 같은 방식으로 음성 입력을 받을 수 있게 해 줍니다.
 */
public abstract class BaseActivity extends AppCompatActivity {

    /** 음성 인식 결과를 받는 곳 */
    public interface VoiceResultListener {
        void onVoiceResult(String spokenText);
    }

    private VoiceResultListener voiceResultListener;
    private ActivityResultLauncher<Intent> voiceLauncher;

    @Override
    protected void attachBaseContext(Context newBase) {
        Configuration configuration = new Configuration(newBase.getResources().getConfiguration());
        if (Prefs.with(newBase).isBiggerTextOn()) {
            configuration.fontScale = configuration.fontScale * 1.3f;
            super.attachBaseContext(newBase.createConfigurationContext(configuration));
        } else {
            super.attachBaseContext(newBase);
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        voiceLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                new ActivityResultCallback<ActivityResult>() {
                    @Override
                    public void onActivityResult(ActivityResult result) {
                        VoiceResultListener listener = voiceResultListener;
                        voiceResultListener = null;
                        if (listener == null) {
                            return;
                        }
                        if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                            listener.onVoiceResult(null);
                            return;
                        }
                        ArrayList<String> matches = result.getData()
                                .getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                        listener.onVoiceResult(
                                matches == null || matches.isEmpty() ? null : matches.get(0));
                    }
                });
    }

    /**
     * 시스템 음성 입력 화면을 띄웁니다. 큰 마이크 그림이 나와 어르신도 알아보기 쉽습니다.
     */
    protected void startVoiceInput(String prompt, VoiceResultListener listener) {
        this.voiceResultListener = listener;
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        // 음성 인식은 IETF 언어 태그(ko-KR)를 받습니다.
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREA.toLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.KOREA.toLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, prompt);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            SpeechManager.get(this).stop();
            voiceLauncher.launch(intent);
        } catch (Exception e) {
            voiceResultListener = null;
            Toast.makeText(this,
                    "이 휴대폰에서는 말로 입력할 수 없어요. 글자로 입력해 주세요.",
                    Toast.LENGTH_LONG).show();
            listener.onVoiceResult(null);
        }
    }
}
