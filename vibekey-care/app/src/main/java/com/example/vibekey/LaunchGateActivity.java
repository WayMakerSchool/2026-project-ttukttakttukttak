package com.example.vibekey;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

/**
 * 잠금화면 위에서 잠깐 떴다 사라지는, 화면이 없는 "문지기" 액티비티입니다.
 *
 * <p><b>왜 필요한가</b><br>
 * 이 기기는 목에 걸거나 주머니에 넣고 다닙니다. 즉 <b>단추를 누르는 순간 폰은 거의 항상
 * 잠겨 있습니다.</b> 그런데 우리가 여는 앱(전화·지도·사진)은 남의 앱이라
 * "잠금화면 위에 보이기"를 선언해 두지 않았습니다. 그래서 서비스가 그 앱을 그냥 열면
 * 앱은 <b>잠금화면 뒤에서</b> 열립니다 — 어르신 눈에는 아무 일도 안 일어난 것과 같습니다.
 *
 * <p>우리 앱 화면({@link MainActivity})만 잠금화면 위에 뜨도록 돼 있었고,
 * 정작 <b>단추가 여는 앱들</b>은 그 처리가 빠져 있었습니다. 이 액티비티가 그 자리를 채웁니다.
 *
 * <p><b>하는 일</b>
 * <pre>
 *   단추 눌림 → (잠겨 있으면) 이 액티비티가 잠금화면 위로 올라옴
 *            → 잠금 해제를 요청
 *            → 풀리면 그때 대상 앱을 연다
 *            → 스스로 사라진다
 * </pre>
 *
 * <p><b>비밀번호를 우회하지 않습니다.</b> 화면 잠금이 걸려 있으면(PIN·패턴·지문)
 * 안드로이드가 사용자에게 잠금 해제를 요구하고, 풀린 뒤에야 앱이 열립니다.
 * 그렇게 하지 않으면 이 기기를 주운 사람이 단추만 눌러 남의 전화·사진을 열 수 있습니다.
 * 밀어서 잠금 해제(보안 잠금 없음)라면 사용자가 아무것도 하지 않아도 바로 열립니다.
 */
public class LaunchGateActivity extends Activity {

    private static final String EXTRA_SLOT = "slot";
    private static final String EXTRA_SOURCE = "source";

    /**
     * 잠금 해제를 거쳐 이 버튼의 앱을 엽니다.
     * 잠겨 있지 않을 때는 {@link AppLauncher}가 이 액티비티를 거치지 않고 바로 엽니다.
     */
    public static void open(Context context, int slot, String source) {
        Intent intent = new Intent(context, LaunchGateActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_SLOT, slot)
                .putExtra(EXTRA_SOURCE, source);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 잠금화면 위로 올라오고, 꺼져 있던 화면도 켠다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }

        final int slot = getIntent().getIntExtra(EXTRA_SLOT, 0);
        final String source = getIntent().getStringExtra(EXTRA_SOURCE);
        if (slot < 1 || slot > Prefs.SLOT_COUNT) {
            finish();
            return;
        }

        // 실제로 보이는 화면을 붙입니다. 투명하게 두면 안드로이드가 "보이는 화면"으로
        // 치지 않아 잠금 해제 요청이 거절되고, 어르신 눈에도 아무 일이 없어 보입니다.
        setContentView(R.layout.activity_launch_gate);
        showWhatIsOpening(slot);

        KeyguardManager keyguard =
                (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);

        if (keyguard == null || !keyguard.isKeyguardLocked()) {
            launchAndClose(slot, source);          // 이미 풀려 있습니다
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 잠금 해제를 요청하고, 풀린 뒤에 엽니다.
            // 보안 잠금이면 안드로이드가 사용자에게 인증을 요구합니다 — 우회하지 않습니다.
            keyguard.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override
                public void onDismissSucceeded() {
                    launchAndClose(slot, source);
                }

                @Override
                public void onDismissCancelled() {
                    // 어르신이 잠금 해제를 그만두셨습니다. 아무것도 열지 않습니다.
                    SpeechManager.get(LaunchGateActivity.this)
                            .speakIfEnabled(LaunchGateActivity.this, getString(R.string.gate_cancelled));
                    finish();
                }

                @Override
                public void onDismissError() {
                    // 잠금을 풀 수 없는 상태입니다. 왜 안 열렸는지는 알려 드려야 합니다.
                    Toast.makeText(LaunchGateActivity.this,
                            R.string.gate_cancelled, Toast.LENGTH_LONG).show();
                    finish();
                }
            });
        } else {
            // API 26 미만에는 요청 방식이 없습니다. 창 플래그로 잠금화면을 걷어 봅니다.
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
            launchAndClose(slot, source);
        }
    }

    /** 어떤 앱이 열리는 중인지 보여 줍니다. 기다리는 동안 화면이 비어 있지 않게. */
    private void showWhatIsOpening(int slot) {
        Prefs prefs = Prefs.with(this);
        String label = prefs.getSlotLabel(slot);
        TextView name = findViewById(R.id.gateApp);
        name.setText(TextUtils.isEmpty(label) ? getString(R.string.gate_opening) : label);

        AppItem item = new AppRepository(this).findByPackage(prefs.getSlotPackage(slot));
        if (item != null && item.icon != null) {
            ((ImageView) findViewById(R.id.gateIcon)).setImageDrawable(item.icon);
        }
    }

    private void launchAndClose(int slot, String source) {
        // 잠금이 풀린 지금 실행합니다. 여기서 runSlot 을 다시 부르면 문지기가 또 열려
        // 무한히 겹치므로, 문지기를 거치지 않는 runSlotNow 를 부릅니다.
        AppLauncher.runSlotNow(this, slot, source);
        finish();
        overridePendingTransition(0, 0);
    }
}
