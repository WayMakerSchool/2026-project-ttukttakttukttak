package com.example.vibekey;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;

/**
 * 설정 화면.
 *  · 제미나이 API 키 입력/확인
 *  · 말소리 안내, 진동, 큰 글씨 같은 사용 편의 설정
 *  · 삼성 루틴 알림 켜기/끄기
 *  · 앱이 제대로 동작하는 데 필요한 권한 바로 가기
 */
public class SettingsActivity extends BaseActivity {

    private Prefs prefs;
    private GeminiClient gemini;

    private EditText apiKeyInput;
    private TextView apiKeyStatus;
    private TextView overlayStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = Prefs.with(this);
        gemini = new GeminiClient(this);

        apiKeyInput = findViewById(R.id.apiKeyInput);
        apiKeyStatus = findViewById(R.id.apiKeyStatus);
        overlayStatus = findViewById(R.id.overlayStatus);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        setupApiKeySection();
        setupSwitches();
        setupRoutineSection();
        setupPermissionSection();

        findViewById(R.id.btnDiagnostics).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                DiagnosticsActivity.open(SettingsActivity.this);
            }
        });

        // 처음 설정을 다시 하고 싶으실 때. (가족이 대신 다시 잡아 드릴 때도 씁니다)
        findViewById(R.id.btnRedoOnboarding).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                OnboardingActivity.open(SettingsActivity.this, true);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateApiKeyStatus();
        updatePermissionStatus();
    }

    // ------------------------------------------------------------------ AI 키

    private void setupApiKeySection() {
        findViewById(R.id.btnSaveKey).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String key = apiKeyInput.getText().toString().trim();
                prefs.setGeminiApiKey(key);
                apiKeyInput.setText("");
                updateApiKeyStatus();
                Haptics.success(SettingsActivity.this);
                String message = TextUtils.isEmpty(key) ? "키를 지웠어요." : "키를 저장했어요.";
                Toast.makeText(SettingsActivity.this, message, Toast.LENGTH_SHORT).show();
                SpeechManager.get(SettingsActivity.this).speakIfEnabled(SettingsActivity.this, message);
            }
        });

        findViewById(R.id.btnTestKey).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testApiKey();
            }
        });
    }

    private void updateApiKeyStatus() {
        if (prefs.hasGeminiApiKey()) {
            apiKeyStatus.setText("지금 키가 들어 있어요. (" + maskKey(prefs.getGeminiApiKey()) + ")");
            apiKeyStatus.setTextColor(ContextCompat.getColor(this, R.color.vk_success));
        } else {
            apiKeyStatus.setText("아직 키가 없어요. AI 기능을 쓰려면 키가 필요합니다.");
            apiKeyStatus.setTextColor(ContextCompat.getColor(this, R.color.vk_danger));
        }
    }

    private String maskKey(String key) {
        if (TextUtils.isEmpty(key) || key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 4) + "…" + key.substring(key.length() - 4);
    }

    private void testApiKey() {
        if (!prefs.hasGeminiApiKey()) {
            Toast.makeText(this, "먼저 키를 저장해 주세요.", Toast.LENGTH_LONG).show();
            return;
        }
        final AlertDialog waiting = new MaterialAlertDialogBuilder(this)
                .setMessage("AI에 연결해 보는 중이에요…")
                .setCancelable(false)
                .show();

        gemini.testApiKey(new GeminiClient.Callback<String>() {
            @Override
            public void onSuccess(String result) {
                dismiss(waiting);
                new MaterialAlertDialogBuilder(SettingsActivity.this)
                        .setTitle("연결 성공")
                        .setMessage("AI와 잘 연결되었어요.\n이제 말로 물어보실 수 있습니다.")
                        .setPositiveButton(R.string.ok, null)
                        .show();
                SpeechManager.get(SettingsActivity.this)
                        .speakIfEnabled(SettingsActivity.this, "AI와 잘 연결되었어요.");
            }

            @Override
            public void onError(String friendlyMessage) {
                dismiss(waiting);
                new MaterialAlertDialogBuilder(SettingsActivity.this)
                        .setTitle("연결 실패")
                        .setMessage(friendlyMessage)
                        .setPositiveButton(R.string.ok, null)
                        .show();
            }
        });
    }

    // ------------------------------------------------------------------ 사용 편의

    private void setupSwitches() {
        SwitchMaterial voice = findViewById(R.id.switchVoice);
        voice.setChecked(prefs.isVoiceFeedbackOn());
        voice.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.setVoiceFeedbackOn(isChecked);
                if (isChecked) {
                    SpeechManager.get(SettingsActivity.this).speak("이제 소리로 알려 드릴게요.");
                } else {
                    SpeechManager.get(SettingsActivity.this).stop();
                }
            }
        });

        SwitchMaterial vibrate = findViewById(R.id.switchVibrate);
        vibrate.setChecked(prefs.isHapticOn());
        vibrate.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.setHapticOn(isChecked);
                if (isChecked) {
                    Haptics.success(SettingsActivity.this);
                }
            }
        });

        SwitchMaterial bigger = findViewById(R.id.switchBigger);
        bigger.setChecked(prefs.isBiggerTextOn());
        bigger.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.setBiggerTextOn(isChecked);
                // 글자 크기는 화면을 다시 그려야 적용됩니다.
                recreate();
            }
        });
    }

    // ------------------------------------------------------------------ 삼성 루틴

    private void setupRoutineSection() {
        SwitchMaterial routine = findViewById(R.id.switchRoutine);
        routine.setChecked(prefs.isRoutineBroadcastOn());
        routine.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.setRoutineBroadcastOn(isChecked);
            }
        });

        findViewById(R.id.btnOpenRoutine).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, RoutineSetupActivity.class));
            }
        });
    }

    // ------------------------------------------------------------------ 권한

    private void setupPermissionSection() {
        findViewById(R.id.btnOverlayPermission).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                }
            }
        });

        findViewById(R.id.btnBatteryPermission).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 배터리 절약에서 빼 두어야 버튼 감지가 계속 살아 있습니다.
                try {
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                } catch (Exception e) {
                    Toast.makeText(SettingsActivity.this,
                            "이 휴대폰에서는 설정 화면을 바로 열 수 없어요.", Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void updatePermissionStatus() {
        boolean overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(this);
        boolean batteryOk = isIgnoringBatteryOptimizations();

        StringBuilder sb = new StringBuilder();
        sb.append(overlayOk ? "✅ " : "⚠️ ").append("다른 앱 위에 표시: ")
                .append(overlayOk ? "허락됨" : "아직 허락 안 됨").append('\n');
        sb.append(batteryOk ? "✅ " : "⚠️ ").append("배터리 절약 예외: ")
                .append(batteryOk ? "설정됨" : "아직 설정 안 됨");
        overlayStatus.setText(sb.toString());
        overlayStatus.setTextColor(ContextCompat.getColor(this,
                overlayOk && batteryOk ? R.color.vk_success : R.color.vk_text));
    }

    private boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void dismiss(AlertDialog dialog) {
        if (dialog != null && dialog.isShowing() && !isFinishing()) {
            dialog.dismiss();
        }
    }
}
