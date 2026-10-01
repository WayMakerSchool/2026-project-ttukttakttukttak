package com.example.vibekey;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

/**
 * 홈 화면.
 *
 * 어르신 사용성을 위해 지킨 원칙
 *  1. 한 화면에 꼭 필요한 것만 둡니다. (지금 상태 · 버튼 3개 · 도움 받기)
 *  2. 모든 글자는 크게, 배경과 글자의 대비는 강하게.
 *  3. 누르는 곳은 손가락보다 크게(72dp 이상)하고, 무엇을 하는 단추인지 말로 적습니다.
 *  4. 중요한 변화는 화면·소리·진동 세 가지로 함께 알립니다.
 */
public class MainActivity extends BaseActivity {

    private TextView statusTextView;
    private TextView statusDescription;
    private View statusCard;
    private ImageView statusIcon;

    /** 기기 그림 아래에 붙는 버튼별 칸 (아이콘 · 앱 이름) */
    private final ImageView[] mapIcons = new ImageView[Prefs.SLOT_COUNT];
    private final TextView[] mapNames = new TextView[Prefs.SLOT_COUNT];

    private Prefs prefs;
    private AppRepository appRepository;
    private GeminiClient gemini;

    /** 첫 설정 화면은 한 번만 띄웁니다. (뒤로 가기를 눌러도 다시 뜨지 않게) */
    private boolean onboardingLaunched;
    /** 권한 안내도 화면당 한 번만 여쭙니다. */
    private boolean overlayAsked;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (UsbSerialService.ACTION_STATUS_CHANGED.equals(intent.getAction())) {
                boolean isConnected = intent.getBooleanExtra(UsbSerialService.EXTRA_IS_CONNECTED, false);
                String description = intent.getStringExtra(UsbSerialService.EXTRA_DESCRIPTION);
                updateUiState(isConnected, description);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showOverLockScreen();
        setContentView(R.layout.activity_main);

        prefs = Prefs.with(this);
        appRepository = new AppRepository(this);
        gemini = new GeminiClient(this);

        statusTextView = findViewById(R.id.statusTextView);
        statusDescription = findViewById(R.id.statusDescription);
        statusCard = findViewById(R.id.statusCard);
        statusIcon = findViewById(R.id.statusIcon);

        bindHardwareMap();

        findViewById(R.id.btnAiHelper).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.tap(MainActivity.this);
                startActivity(new Intent(MainActivity.this, AiAssistantActivity.class)
                        .putExtra(AiAssistantActivity.EXTRA_AUTO_LISTEN, true));
            }
        });

        findViewById(R.id.btnAiRecommend).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.tap(MainActivity.this);
                askAiForRecommendation();
            }
        });

        findViewById(R.id.btnSettings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        findViewById(R.id.btnRoutine).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, RoutineSetupActivity.class));
            }
        });

        // 상태 카드를 누르면 지금 상태를 소리로 읽어 드립니다.
        statusCard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SpeechManager.get(MainActivity.this)
                        .speak(statusTextView.getText() + ". " + statusDescription.getText());
            }
        });

        startUsbService();
        RoutineBridge.refreshShortcuts(this);
    }

    /** USB를 꽂으면 잠금 화면 위에서도 앱이 바로 보이도록 합니다. */
    private void showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager keyguardManager =
                    (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (keyguardManager != null) {
                keyguardManager.requestDismissKeyguard(this, null);
            }
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    // ------------------------------------------------------------------ 버튼 카드

    /** 기기 그림 아래 칸 3개를 찾아 연결합니다. 칸을 누르면 그 단추를 바로 손볼 수 있습니다. */
    private void bindHardwareMap() {
        int[] iconIds = {R.id.mapIcon1, R.id.mapIcon2, R.id.mapIcon3};
        int[] nameIds = {R.id.mapName1, R.id.mapName2, R.id.mapName3};
        int[] columnIds = {R.id.mapColumn1, R.id.mapColumn2, R.id.mapColumn3};

        for (int slot = 1; slot <= Prefs.SLOT_COUNT; slot++) {
            final int slotNumber = slot;
            mapIcons[slot - 1] = findViewById(iconIds[slot - 1]);
            mapNames[slot - 1] = findViewById(nameIds[slot - 1]);
            findViewById(columnIds[slot - 1]).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Haptics.tap(MainActivity.this);
                    showSlotActionDialog(slotNumber);
                }
            });
        }
    }

    /** 단추 하나를 눌렀을 때: 무엇을 할지 큰 글씨로 물어봅니다. */
    private void showSlotActionDialog(final int slot) {
        String appName = prefs.getSlotLabel(slot);
        String actionType = prefs.getSlotActionType(slot);
        String title = getString(R.string.slot_title, slot);
        String message;
        if (TextUtils.isEmpty(appName)) {
            message = "이 단추에는 아직 아무것도 없어요.";
        } else if ("APP".equals(actionType)) {
            message = "지금은 '" + appName + "'" + KoreanParticle.iGa(appName) + " 열려요.";
        } else {
            message = "지금은 '" + appName + "'" + KoreanParticle.iGa(appName) + " 실행돼요.";
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("바꾸기", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        startActivity(new Intent(MainActivity.this, AppPickerActivity.class)
                                .putExtra(AppPickerActivity.EXTRA_SLOT, slot));
                    }
                })
                .setNeutralButton("지금 눌러 보기", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        AppLauncher.runSlot(MainActivity.this, slot, "screen");
                    }
                })
                .setNegativeButton(R.string.close, null)
                .show();
        SpeechManager.get(this).speakIfEnabled(this, title + ". " + message);
    }

    /** 각 단추가 여는 앱의 아이콘과 이름을 기기 그림 아래에 그려 줍니다. */
    private void refreshHardwareMap() {
        for (int slot = 1; slot <= Prefs.SLOT_COUNT; slot++) {
            ImageView icon = mapIcons[slot - 1];
            TextView name = mapNames[slot - 1];
            if (icon == null || name == null) {
                continue;
            }

            String actionType = prefs.getSlotActionType(slot);
            String label = prefs.getSlotLabel(slot);

            if (!"APP".equals(actionType)) {
                if (TextUtils.isEmpty(label)) {
                    name.setText(R.string.slot_empty);
                    name.setTextColor(ContextCompat.getColor(this, R.color.vk_text_secondary));
                    icon.setImageResource(R.drawable.ic_apps);
                    icon.setColorFilter(ContextCompat.getColor(this, R.color.vk_text_secondary));
                } else {
                    name.setText(label);
                    name.setTextColor(ContextCompat.getColor(this, R.color.vk_text));
                    icon.setImageResource(quickActionIcon(actionType));
                    icon.setColorFilter(ContextCompat.getColor(this, R.color.vk_primary));
                }
                continue;
            }

            String packageName = prefs.getSlotPackage(slot);
            if (TextUtils.isEmpty(packageName)) {
                name.setText(R.string.slot_empty);
                name.setTextColor(ContextCompat.getColor(this, R.color.vk_text_secondary));
                icon.setImageResource(R.drawable.ic_apps);
                icon.setColorFilter(ContextCompat.getColor(this, R.color.vk_text_secondary));
                continue;
            }

            AppItem item = appRepository.findByPackage(packageName);
            if (item != null && item.icon != null) {
                icon.clearColorFilter();
                icon.setImageDrawable(item.icon);
                name.setText(item.label);
                name.setTextColor(ContextCompat.getColor(this, R.color.vk_text));
            } else {
                // 앱이 지워졌을 수 있습니다.
                icon.setImageResource(R.drawable.ic_alert);
                icon.setColorFilter(ContextCompat.getColor(this, R.color.vk_danger));
                name.setText((TextUtils.isEmpty(label) ? packageName : label) + "\n(지워짐)");
                name.setTextColor(ContextCompat.getColor(this, R.color.vk_danger));
            }
        }
    }

    private static int quickActionIcon(String actionType) {
        switch (actionType) {
            case "DIAL":
                return R.drawable.ic_phone;
            case "SMS":
                return R.drawable.ic_keyboard;
            case "MAPS":
                return R.drawable.ic_search;
            default:
                return R.drawable.ic_apps;
        }
    }

    // ------------------------------------------------------------------ AI 추천

    private void askAiForRecommendation() {
        if (!gemini.hasApiKey()) {
            showNeedApiKeyDialog();
            return;
        }

        final AlertDialog waiting = showWaitingDialog("AI가 어울리는 앱을 고르고 있어요…");
        SpeechManager.get(this).speakIfEnabled(this, "잠시만 기다려 주세요.");

        gemini.recommendSlots(appRepository.buildAppCatalogForPrompt(120),
                new GeminiClient.Callback<List<GeminiClient.SlotSuggestion>>() {
                    @Override
                    public void onSuccess(List<GeminiClient.SlotSuggestion> suggestions) {
                        dismiss(waiting);
                        showRecommendation(suggestions);
                    }

                    @Override
                    public void onError(String friendlyMessage) {
                        dismiss(waiting);
                        showMessageDialog("잠시 문제가 있어요", friendlyMessage);
                    }
                });
    }

    private void showRecommendation(final List<GeminiClient.SlotSuggestion> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            showMessageDialog("추천 결과 없음", "지금은 추천할 앱을 찾지 못했어요. 잠시 뒤에 다시 해 주세요.");
            return;
        }

        StringBuilder message = new StringBuilder();
        for (GeminiClient.SlotSuggestion s : suggestions) {
            AppItem item = appRepository.findByPackage(s.packageName);
            String name = item != null ? item.label
                    : (TextUtils.isEmpty(s.label) ? s.packageName : s.label);
            message.append(s.slot).append("번 버튼 → ").append(name).append('\n');
            if (!TextUtils.isEmpty(s.reason)) {
                message.append("   ").append(s.reason).append("\n");
            }
            message.append('\n');
        }
        message.append("이대로 넣을까요?");

        new MaterialAlertDialogBuilder(this)
                .setTitle("AI 추천")
                .setMessage(message.toString())
                .setPositiveButton("네, 넣어 주세요", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        applyRecommendation(suggestions);
                    }
                })
                .setNegativeButton("아니요", null)
                .show();
        SpeechManager.get(this).speakIfEnabled(this, "추천을 준비했어요. 화면을 확인해 주세요.");
    }

    private void applyRecommendation(List<GeminiClient.SlotSuggestion> suggestions) {
        int applied = 0;
        for (GeminiClient.SlotSuggestion s : suggestions) {
            if (s.slot < 1 || s.slot > Prefs.SLOT_COUNT) {
                continue;
            }
            AppItem item = appRepository.findByPackage(s.packageName);
            if (item == null) {
                continue; // AI가 없는 앱을 골랐다면 넣지 않습니다.
            }
            prefs.setSlot(s.slot, item.packageName, item.label);
            applied++;
        }
        refreshHardwareMap();
        RoutineBridge.refreshShortcuts(this);
        UsbSerialService.pushSlotMap(this);   // 기기 플래시에도 같은 값을 적어 둡니다

        String message = applied > 0
                ? applied + "개 버튼에 앱을 넣었어요."
                : "추천한 앱이 이 휴대폰에 없어서 넣지 못했어요.";
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        SpeechManager.get(this).speakIfEnabled(this, message);
        Haptics.success(this);
    }

    private void showNeedApiKeyDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("AI 준비가 필요해요")
                .setMessage(getString(R.string.ai_no_key))
                .setPositiveButton("설정 열기", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                    }
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    // ------------------------------------------------------------------ 상태 표시

    private void updateUiState(final boolean isConnected, final String description) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isConnected) {
                    statusCard.setVisibility(View.VISIBLE);
                    statusTextView.setText(R.string.status_connected);
                    statusTextView.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.vk_success));
                    statusCard.setBackgroundResource(R.drawable.bg_status_ok);
                    statusIcon.setImageResource(R.drawable.ic_check);
                    statusIcon.setColorFilter(ContextCompat.getColor(MainActivity.this, R.color.vk_success));
                    statusDescription.setText(TextUtils.isEmpty(description)
                            ? getString(R.string.status_hint_connected) : description);
                } else {
                    // 연결 안 됨 경고는 더 이상 보여 주지 않습니다.
                    statusCard.setVisibility(View.GONE);
                }
            }
        });
    }

    // ------------------------------------------------------------------ 생명주기

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(UsbSerialService.ACTION_STATUS_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
        appRepository.invalidate();
        refreshHardwareMap();
        RoutineBridge.refreshShortcuts(this);

        // 처음 켠 분이라면 설정 화면부터 보여 드립니다.
        // (자주 하는 일을 고르시면 AI가 단추 1·2·3번을 알아서 정해 드립니다.)
        if (!onboardingLaunched && !prefs.isOnboarded()) {
            onboardingLaunched = true;
            OnboardingActivity.open(this, false);
            return;
        }

        // 첫 설정을 마친 뒤에 권한을 여쭙습니다. 한꺼번에 물으면 어르신이 당황하십니다.
        if (!overlayAsked) {
            overlayAsked = true;
            checkOverlayPermission();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(statusReceiver);
        } catch (IllegalArgumentException ignored) {
            // 이미 해제된 경우입니다.
        }
    }

    private void startUsbService() {
        UsbSerialService.start(this);
    }

    private void checkOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("한 가지만 허락해 주세요")
                .setMessage("버튼을 눌렀을 때 앱이 저절로 열리려면\n'다른 앱 위에 표시' 허락이 필요합니다.\n\n"
                        + "설정으로 가서 '바이브키'를 켜 주세요.")
                .setPositiveButton("설정으로 가기", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    }
                })
                .setNegativeButton("나중에", null)
                .show();
    }

    // ------------------------------------------------------------------ 대화상자 도우미

    private void showMessageDialog(String title, String message) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private AlertDialog showWaitingDialog(String message) {
        return new MaterialAlertDialogBuilder(this)
                .setMessage(message)
                .setCancelable(false)
                .show();
    }

    private void dismiss(AlertDialog dialog) {
        if (dialog != null && dialog.isShowing() && !isFinishing()) {
            dialog.dismiss();
        }
    }
}
