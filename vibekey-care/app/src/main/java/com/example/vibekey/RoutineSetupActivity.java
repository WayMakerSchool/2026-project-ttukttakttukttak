package com.example.vibekey;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

/**
 * 삼성 "모드 및 루틴"과 연결하는 방법을 1·2·3 단계로 안내하는 화면입니다.
 * 안내만 하는 것이 아니라, 루틴이 실제로 부르는 것과 똑같은 방식으로
 * 지금 바로 시험해 볼 수 있게 했습니다.
 */
public class RoutineSetupActivity extends BaseActivity {

    /** 삼성 "모드 및 루틴" 앱 패키지명 */
    private static final String ROUTINE_PACKAGE = "com.samsung.android.app.routines";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_routine);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        showAvailability();

        findViewById(R.id.btnMakeShortcuts).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RoutineBridge.refreshShortcuts(RoutineSetupActivity.this);
                // 루틴 앱의 "앱 실행" 목록은 동적 바로가기를 읽지 않으므로,
                // 버튼별 실행 항목까지 함께 켜 줘야 루틴에서 고를 수 있습니다.
                boolean entriesOn = RoutineBridge.setLauncherEntriesEnabled(
                        RoutineSetupActivity.this, true);
                Haptics.success(RoutineSetupActivity.this);
                String message = entriesOn
                        ? "다 됐어요. 루틴에서 '앱 실행'을 고르면 '바이브키 1번 버튼'이 보여요."
                        : "바로가기를 만들었어요. 이제 루틴에서 고를 수 있어요.";
                Toast.makeText(RoutineSetupActivity.this, message, Toast.LENGTH_LONG).show();
                SpeechManager.get(RoutineSetupActivity.this)
                        .speakIfEnabled(RoutineSetupActivity.this, message);
            }
        });

        findViewById(R.id.btnOpenRoutineApp).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openRoutineApp();
            }
        });

        findViewById(R.id.btnTestRoutine1).setOnClickListener(testListener(1));
        findViewById(R.id.btnTestRoutine2).setOnClickListener(testListener(2));
        findViewById(R.id.btnTestRoutine3).setOnClickListener(testListener(3));

        final TextView detail = findViewById(R.id.routineDetail);
        detail.setText(RoutineBridge.buildHowToText(this));

        findViewById(R.id.btnCopyInfo).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager clipboard =
                        (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(
                            ClipData.newPlainText("VibeKey Routine", detail.getText()));
                    Toast.makeText(RoutineSetupActivity.this, "복사했어요.", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private View.OnClickListener testListener(final int slot) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.tap(RoutineSetupActivity.this);
                // 루틴이 부르는 것과 똑같은 경로(RoutineActionActivity)로 실행해 봅니다.
                Intent intent = new Intent(RoutineSetupActivity.this, RoutineActionActivity.class);
                intent.setAction(RoutineBridge.ACTION_RUN_BUTTON);
                intent.setData(RoutineBridge.runButtonUri(slot));
                intent.putExtra(RoutineBridge.EXTRA_BUTTON, slot);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
        };
    }

    /** 삼성 "모드 및 루틴"이 있는 휴대폰인지 알려 줍니다. */
    private void showAvailability() {
        TextView availability = findViewById(R.id.routineAvailability);
        if (isRoutineAppInstalled()) {
            availability.setText("✅ 이 휴대폰에는 삼성 '모드 및 루틴'이 있어요. 아래 순서대로 연결하시면 됩니다.");
            availability.setTextColor(ContextCompat.getColor(this, R.color.vk_success));
        } else {
            availability.setText("ℹ️ 삼성 '모드 및 루틴'이 없는 휴대폰이에요.\n"
                    + "대신 태스커·매크로드로이드 같은 자동화 앱에서 아래 정보로 연결할 수 있어요.");
            availability.setTextColor(ContextCompat.getColor(this, R.color.vk_text));
        }
    }

    private boolean isRoutineAppInstalled() {
        try {
            getPackageManager().getPackageInfo(ROUTINE_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void openRoutineApp() {
        // 1) 모드 및 루틴 앱을 바로 엽니다.
        Intent launch = getPackageManager().getLaunchIntentForPackage(ROUTINE_PACKAGE);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
            return;
        }
        // 2) 없으면 휴대폰 설정 화면이라도 열어 드립니다.
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
            Toast.makeText(this, "설정에서 '모드 및 루틴'을 찾아 주세요.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "설정 화면을 열 수 없어요.", Toast.LENGTH_LONG).show();
        }
    }
}
