package com.example.vibekey;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

/**
 * 앱을 여는 것 말고, 표준 안드로이드 인텐트로 바로 처리할 수 있는 동작들입니다.
 * 특정 앱(카카오T 등)의 비공식 딥링크는 기기·버전마다 깨지기 쉬워 쓰지 않고,
 * 안드로이드가 공식적으로 정한 tel: / smsto: / geo: 규격만 사용합니다.
 */
public final class IntentActions {

    private static final String TAG = "VibeKeyIntentActions";

    private IntentActions() {
    }

    /** 전화 앱을 그 번호로 걸 준비가 된 채로 엽니다. (실수로 바로 걸리지 않도록 통화 버튼은 직접 누르셔야 합니다.) */
    public static boolean dial(Context context, String phoneNumber) {
        if (TextUtils.isEmpty(phoneNumber)) {
            return false;
        }
        Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(phoneNumber)));
        return start(context, intent);
    }

    /** 문자 앱을 번호·내용이 채워진 채로 엽니다. (보내기는 직접 누르셔야 합니다.) */
    public static boolean sms(Context context, String phoneNumber, String body) {
        Uri uri = TextUtils.isEmpty(phoneNumber) ? Uri.parse("smsto:") : Uri.parse("smsto:" + Uri.encode(phoneNumber));
        Intent intent = new Intent(Intent.ACTION_SENDTO, uri);
        if (!TextUtils.isEmpty(body)) {
            intent.putExtra("sms_body", body);
        }
        return start(context, intent);
    }

    /** 지도 앱에서 목적지를 검색해 보여 줍니다. */
    public static boolean navigate(Context context, String destination) {
        if (TextUtils.isEmpty(destination)) {
            return false;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("geo:0,0?q=" + Uri.encode(destination)));
        return start(context, intent);
    }

    private static boolean start(Context context, Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (intent.resolveActivity(context.getPackageManager()) == null) {
                return false;
            }
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start action intent", e);
            return false;
        }
    }
}
