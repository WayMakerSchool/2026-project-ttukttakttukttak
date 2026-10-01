package com.example.vibekey;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

/**
 * 앱 전체 설정을 한 곳에서 읽고 쓰기 위한 도우미 클래스입니다.
 * 기존 버전에서 쓰던 저장 키("target_package_name" 등)를 그대로 유지해
 * 업데이트해도 사용자가 지정해 둔 앱이 사라지지 않습니다.
 */
public final class Prefs {

    public static final String PREFS_NAME = "VibeKeySettings";
    public static final int SLOT_COUNT = 3;

    private static final String KEY_GEMINI_API_KEY = "gemini_api_key";
    private static final String KEY_VOICE_FEEDBACK = "voice_feedback";
    private static final String KEY_HAPTIC = "haptic";
    private static final String KEY_BIGGER_TEXT = "bigger_text";
    private static final String KEY_ROUTINE_BROADCAST = "routine_broadcast";
    private static final String KEY_ONBOARDED = "onboarded";
    private static final String KEY_LAST_AI_ANSWER = "last_ai_answer";

    private final SharedPreferences prefs;

    private Prefs(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static Prefs with(Context context) {
        return new Prefs(context);
    }

    // ---------------------------------------------------------------- 버튼 슬롯

    public static String packageKey(int slot) {
        return slot == 1 ? "target_package_name" : "target_package_name_" + slot;
    }

    public static String labelKey(int slot) {
        return slot == 1 ? "target_app_label" : "target_app_label_" + slot;
    }

    public String getSlotPackage(int slot) {
        String fallback = slot == 1 ? "com.google.android.apps.maps" : "";
        return prefs.getString(packageKey(slot), fallback);
    }

    public String getSlotLabel(int slot) {
        String fallback = slot == 1 ? "Google 지도" : "";
        return prefs.getString(labelKey(slot), fallback);
    }

    public boolean hasSlot(int slot) {
        if (!"APP".equals(getSlotActionType(slot))) {
            return !TextUtils.isEmpty(getSlotLabel(slot));
        }
        return !TextUtils.isEmpty(getSlotPackage(slot));
    }

    public void setSlot(int slot, String packageName, String label) {
        prefs.edit()
                .putString(packageKey(slot), packageName)
                .putString(labelKey(slot), label)
                .putString(actionTypeKey(slot), "APP")
                .apply();
    }

    public void clearSlot(int slot) {
        prefs.edit()
                .remove(packageKey(slot))
                .remove(labelKey(slot))
                .remove(actionTypeKey(slot))
                .remove(actionNumberKey(slot))
                .remove(actionTextKey(slot))
                .apply();
    }


    /**
     * 사용자가(또는 AI가) 실제로 정해 둔 버튼이 하나라도 있는지 봅니다.
     *
     * <p>{@link #hasSlot}과 다른 점: 1번 버튼에는 처음부터 기본값(지도)이 들어 있어
     * 아무것도 안 정한 새 폰에서도 hasSlot(1)은 true 입니다. 기기에 저장해 둔 매핑을
     * 되찾아 올지 판단할 때는 "정말 아무것도 없는 상태"를 알아야 하므로,
     * 여기서는 기본값을 빼고 <b>실제로 저장된 값</b>만 셉니다.
     */
    public boolean hasAnyExplicitSlot() {
        for (int slot = 1; slot <= SLOT_COUNT; slot++) {
            if (prefs.contains(packageKey(slot)) || prefs.contains(labelKey(slot))) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 버튼 슬롯: 빠른 동작
    // 앱을 여는 대신 전화 걸기·문자 보내기·길찾기처럼 정해진 동작을 바로 실행하는 슬롯입니다.

    public static String actionTypeKey(int slot) {
        return "slot_action_type_" + slot;
    }

    public static String actionNumberKey(int slot) {
        return "slot_action_number_" + slot;
    }

    public static String actionTextKey(int slot) {
        return "slot_action_text_" + slot;
    }

    /** "APP"(기본값) · "DIAL" · "SMS" · "MAPS" */
    public String getSlotActionType(int slot) {
        return prefs.getString(actionTypeKey(slot), "APP");
    }

    public String getSlotActionNumber(int slot) {
        return prefs.getString(actionNumberKey(slot), "");
    }

    public String getSlotActionText(int slot) {
        return prefs.getString(actionTextKey(slot), "");
    }

    /** 버튼에 앱 대신 빠른 동작(전화·문자·길찾기)을 연결합니다. */
    public void setSlotAction(int slot, String type, String label, String number, String text) {
        prefs.edit()
                .putString(actionTypeKey(slot), type)
                .putString(labelKey(slot), label)
                .putString(actionNumberKey(slot), number == null ? "" : number)
                .putString(actionTextKey(slot), text == null ? "" : text)
                .putString(packageKey(slot), "")
                .apply();
    }

    // ---------------------------------------------------------------- AI 설정

    /** 설정 화면에서 넣은 키를 우선 사용하고, 없으면 빌드 시 주입된 기본 키를 씁니다. */
    public String getGeminiApiKey() {
        String saved = prefs.getString(KEY_GEMINI_API_KEY, "");
        if (!TextUtils.isEmpty(saved)) {
            return saved;
        }
        return BuildConfig.DEFAULT_GEMINI_API_KEY;
    }

    public void setGeminiApiKey(String key) {
        prefs.edit().putString(KEY_GEMINI_API_KEY, key == null ? "" : key.trim()).apply();
    }

    public boolean hasGeminiApiKey() {
        return !TextUtils.isEmpty(getGeminiApiKey());
    }

    public String getLastAiAnswer() {
        return prefs.getString(KEY_LAST_AI_ANSWER, "");
    }

    public void setLastAiAnswer(String answer) {
        prefs.edit().putString(KEY_LAST_AI_ANSWER, answer == null ? "" : answer).apply();
    }

    // ---------------------------------------------------------------- 사용 편의

    public boolean isVoiceFeedbackOn() {
        return prefs.getBoolean(KEY_VOICE_FEEDBACK, true);
    }

    public void setVoiceFeedbackOn(boolean on) {
        prefs.edit().putBoolean(KEY_VOICE_FEEDBACK, on).apply();
    }

    public boolean isHapticOn() {
        return prefs.getBoolean(KEY_HAPTIC, true);
    }

    public void setHapticOn(boolean on) {
        prefs.edit().putBoolean(KEY_HAPTIC, on).apply();
    }

    public boolean isBiggerTextOn() {
        return prefs.getBoolean(KEY_BIGGER_TEXT, false);
    }

    public void setBiggerTextOn(boolean on) {
        prefs.edit().putBoolean(KEY_BIGGER_TEXT, on).apply();
    }

    // ---------------------------------------------------------------- 삼성 루틴

    public boolean isRoutineBroadcastOn() {
        return prefs.getBoolean(KEY_ROUTINE_BROADCAST, true);
    }

    public void setRoutineBroadcastOn(boolean on) {
        prefs.edit().putBoolean(KEY_ROUTINE_BROADCAST, on).apply();
    }

    // ---------------------------------------------------------------- 첫 실행

    public boolean isOnboarded() {
        return prefs.getBoolean(KEY_ONBOARDED, false);
    }

    public void setOnboarded(boolean value) {
        prefs.edit().putBoolean(KEY_ONBOARDED, value).apply();
    }
}
