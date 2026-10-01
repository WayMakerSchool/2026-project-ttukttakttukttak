package com.example.vibekey;

import android.content.Context;
import android.os.Build;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 화면을 보기 어려운 어르신을 위해 안내 문구를 소리로 읽어 주는 도우미입니다.
 * 앱 전체에서 하나의 TextToSpeech 엔진을 함께 씁니다.
 */
public final class SpeechManager {

    private static final String TAG = "VibeKeySpeech";
    private static SpeechManager instance;

    private TextToSpeech tts;
    private boolean ready = false;
    private final List<String> pending = new ArrayList<>();
    private Runnable onDoneListener;

    private SpeechManager(Context context) {
        final Context appContext = context.getApplicationContext();
        tts = new TextToSpeech(appContext, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status != TextToSpeech.SUCCESS) {
                    Log.w(TAG, "TextToSpeech init failed: " + status);
                    return;
                }
                int result = tts.setLanguage(Locale.KOREAN);
                if (result == TextToSpeech.LANG_MISSING_DATA
                        || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Korean TTS voice is not available");
                }
                // 어르신이 알아듣기 쉽도록 조금 천천히 읽습니다.
                tts.setSpeechRate(0.92f);
                tts.setPitch(1.0f);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        notifyDone();
                    }

                    @Override
                    public void onError(String utteranceId) {
                        notifyDone();
                    }
                });
                ready = true;
                flushPending();
            }
        });
    }

    public static synchronized SpeechManager get(Context context) {
        if (instance == null) {
            instance = new SpeechManager(context);
        }
        return instance;
    }

    /** 이전에 읽던 말을 멈추고 새 문장을 읽습니다. */
    public void speak(String text) {
        if (TextUtils.isEmpty(text)) {
            return;
        }
        if (!ready) {
            synchronized (pending) {
                pending.clear();
                pending.add(text);
            }
            return;
        }
        String id = "vk-" + System.currentTimeMillis();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id);
        } else {
            //noinspection deprecation
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
        }
    }

    /** 설정에서 "말소리로 알려 주기"가 켜져 있을 때만 읽습니다. */
    public void speakIfEnabled(Context context, String text) {
        if (Prefs.with(context).isVoiceFeedbackOn()) {
            speak(text);
        }
    }

    public void stop() {
        if (tts != null) {
            tts.stop();
        }
    }

    public boolean isSpeaking() {
        return tts != null && tts.isSpeaking();
    }

    public void setOnDoneListener(Runnable listener) {
        this.onDoneListener = listener;
    }

    private void notifyDone() {
        Runnable listener = onDoneListener;
        if (listener != null) {
            listener.run();
        }
    }

    private void flushPending() {
        List<String> copy;
        synchronized (pending) {
            if (pending.isEmpty()) {
                return;
            }
            copy = new ArrayList<>(pending);
            pending.clear();
        }
        for (String text : copy) {
            speak(text);
        }
    }
}
