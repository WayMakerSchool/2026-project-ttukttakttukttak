package com.example.vibekey;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 삼성 "모드 및 루틴"이 제3자 앱의 동작(Action)을 실행할 때 호출하는 ContentProvider 입니다.
 * (Samsung Routines SDK v3 규격의 호출 방식을 따릅니다.)
 *
 * 루틴 앱은 ContentProvider.call() 로 동작 실행을 요청하며, 우리는 전달받은
 * 파라미터(vibekey_action / button)를 읽어 실제 동작을 수행합니다.
 *
 * ※ 삼성 공식 SDK(routines-sdk .aar)를 app/libs 에 넣으면 SepRoutineActionProvider 를 상속해
 *   더 풍부한 UI(라벨/파라미터 편집 화면)를 붙일 수 있습니다. 이 클래스는 SDK 없이도
 *   동작 실행이 되도록 최소 규격만 직접 구현한 것입니다.
 *   SDK 없이 확실하게 동작하는 경로는 앱 바로가기(RoutineBridge.refreshShortcuts)와
 *   딥링크(vibekey://run/1)이며, 설정 화면의 "삼성 루틴 연결"에서 안내합니다.
 */
public class RoutineActionProvider extends ContentProvider {

    private static final String TAG = "VibeKeyRoutineProv";

    /** 루틴이 넘겨 주는 파라미터 키 */
    private static final String PARAM_ACTION = "vibekey_action";
    private static final String PARAM_BUTTON = "button";

    /** 루틴이 호출하는 메서드 이름들 (SDK v3 규격) */
    private static final String METHOD_ACTION_ENABLED = "onActionEnabled";
    private static final String METHOD_ACTION_DISABLED = "onActionDisabled";
    private static final String METHOD_GET_LABEL = "getLabelParams";
    private static final String METHOD_IS_SUPPORTED = "isSupportedAction";

    private static final String RESULT_SUCCESS = "result_success";
    private static final String RESULT_LABEL = "label_params";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        Log.d(TAG, "routine call: method=" + method + " arg=" + arg);
        Bundle result = new Bundle();

        if (METHOD_IS_SUPPORTED.equals(method)) {
            result.putBoolean(RESULT_SUCCESS, true);
            return result;
        }

        if (METHOD_GET_LABEL.equals(method)) {
            result.putString(RESULT_LABEL, describe(extras, arg));
            result.putBoolean(RESULT_SUCCESS, true);
            return result;
        }

        if (METHOD_ACTION_DISABLED.equals(method)) {
            // 루틴이 해제될 때는 따로 되돌릴 상태가 없습니다.
            result.putBoolean(RESULT_SUCCESS, true);
            return result;
        }

        // onActionEnabled 또는 그 밖의 실행 요청은 모두 "동작 실행"으로 처리합니다.
        boolean handled = execute(extras, arg);
        result.putBoolean(RESULT_SUCCESS, handled);
        return result;
    }

    /** 루틴이 넘긴 파라미터를 해석해 실제 동작을 수행합니다. */
    private boolean execute(@Nullable Bundle extras, @Nullable String arg) {
        if (getContext() == null) {
            return false;
        }
        String action = readAction(extras, arg);
        int slot = readButton(extras, arg);

        if ("open_ai".equals(action)) {
            android.content.Intent intent =
                    new android.content.Intent(getContext(), RoutineActionActivity.class);
            intent.setAction(RoutineBridge.ACTION_OPEN_AI);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            return true;
        }

        if (slot >= 1 && slot <= Prefs.SLOT_COUNT) {
            return AppLauncher.runSlot(getContext(), slot, "routine");
        }

        Log.w(TAG, "Routine call without a usable parameter");
        return false;
    }

    private String describe(@Nullable Bundle extras, @Nullable String arg) {
        int slot = readButton(extras, arg);
        if ("open_ai".equals(readAction(extras, arg))) {
            return "AI 도우미 열기";
        }
        if (slot >= 1 && slot <= Prefs.SLOT_COUNT && getContext() != null) {
            String label = Prefs.with(getContext()).getSlotLabel(slot);
            return TextUtils.isEmpty(label) ? slot + "번 버튼 실행" : slot + "번 버튼 · " + label;
        }
        return "바이브키 실행";
    }

    private String readAction(@Nullable Bundle extras, @Nullable String arg) {
        if (extras != null && extras.containsKey(PARAM_ACTION)) {
            return String.valueOf(extras.get(PARAM_ACTION));
        }
        if (!TextUtils.isEmpty(arg) && arg.startsWith("open_ai")) {
            return "open_ai";
        }
        return "";
    }

    private int readButton(@Nullable Bundle extras, @Nullable String arg) {
        if (extras != null && extras.containsKey(PARAM_BUTTON)) {
            Object value = extras.get(PARAM_BUTTON);
            if (value instanceof Integer) {
                return (Integer) value;
            }
            if (value != null) {
                try {
                    return Integer.parseInt(String.valueOf(value).trim());
                } catch (NumberFormatException ignored) {
                    // 아래 arg 해석으로 넘어갑니다.
                }
            }
        }
        // "button=2" 처럼 문자열 하나로 들어오는 경우도 받아 줍니다.
        if (!TextUtils.isEmpty(arg)) {
            int index = arg.indexOf(PARAM_BUTTON + "=");
            if (index >= 0) {
                String tail = arg.substring(index + PARAM_BUTTON.length() + 1).trim();
                StringBuilder digits = new StringBuilder();
                for (int i = 0; i < tail.length() && Character.isDigit(tail.charAt(i)); i++) {
                    digits.append(tail.charAt(i));
                }
                if (digits.length() > 0) {
                    return Integer.parseInt(digits.toString());
                }
            }
        }
        return 0;
    }

    // ------------------------------------------------------------- 사용하지 않는 표준 메서드

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                        @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        return 0;
    }
}
