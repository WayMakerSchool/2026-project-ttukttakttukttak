package com.example.vibekey;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 휴대폰을 다시 켰을 때 버튼 감지 서비스를 되살립니다.
 *
 * 안드로이드 14부터는 USB 기기가 꽂혀 있지 않으면 connectedDevice 형식의
 * 포그라운드 서비스를 켤 수 없습니다. 그래서 기기가 꽂혀 있을 때만 시작하고,
 * 그렇지 않으면 나중에 기기를 꽂는 순간(USB_DEVICE_ATTACHED)에 앱이 깨어나도록 둡니다.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "VibeKeyBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            return;
        }

        if (!UsbSerialService.hasUsbDevice(context)) {
            Log.d(TAG, "Boot completed, but no USB device attached. Waiting for attach.");
            return;
        }

        Log.d(TAG, "Boot completed with USB device attached, starting UsbSerialService...");
        UsbSerialService.start(context);
    }
}
