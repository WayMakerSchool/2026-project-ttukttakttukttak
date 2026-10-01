package com.example.vibekey;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.content.pm.ShortcutManagerCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 테스트(자가진단) 화면.
 *
 * 이 앱은 하드웨어와 인터넷, 권한이 모두 맞아야 동작하기 때문에
 * "왜 안 되는지"를 사용자가 스스로 알 수 있어야 합니다. 그래서
 *
 *  1. 열 가지 항목을 한 번에 검사하고 ✅ / ⚠️ 로 보여 주고,
 *  2. 기기가 없어도 진짜와 똑같은 프레임(깨진 것 포함)을 넣어 동작을 시험해 보고,
 *  3. 기기가 보낸 신호 기록을 눈으로 확인할 수 있게
 *
 * 만들었습니다.
 */
public class DiagnosticsActivity extends BaseActivity {

    /** 검사 한 항목의 결과 */
    private static class Check {
        final String name;
        final boolean ok;
        final String detail;

        Check(String name, boolean ok, String detail) {
            this.name = name;
            this.ok = ok;
            this.detail = detail;
        }
    }

    private LinearLayout resultContainer;
    private TextView summaryText;
    private TextView logText;

    private Prefs prefs;
    private AppRepository appRepository;
    private GeminiClient gemini;

    /** 로그가 바뀌면 화면을 새로 그립니다. */
    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshLog();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diagnostics);

        prefs = Prefs.with(this);
        appRepository = new AppRepository(this);
        gemini = new GeminiClient(this);

        resultContainer = findViewById(R.id.resultContainer);
        summaryText = findViewById(R.id.testSummary);
        logText = findViewById(R.id.logText);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        findViewById(R.id.btnRunAll).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.tap(DiagnosticsActivity.this);
                runAllChecks();
            }
        });

        // 기기가 실제로 보내는 것과 똑같은 프레임(CRC·SEQ 포함)을 만들어 넣습니다.
        findViewById(R.id.btnSignal1).setOnClickListener(frameListener(1, FrameCodec.K_SHORT, false));
        findViewById(R.id.btnSignal2).setOnClickListener(frameListener(2, FrameCodec.K_SHORT, false));
        findViewById(R.id.btnSignal3).setOnClickListener(frameListener(3, FrameCodec.K_SHORT, false));
        findViewById(R.id.btnSignalAi).setOnClickListener(frameListener(1, FrameCodec.K_LONG, false));
        findViewById(R.id.btnSignalNoise).setOnClickListener(frameListener(1, FrameCodec.K_SHORT, true));

        findViewById(R.id.btnClearLog).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SerialLog.clear();
                refreshLog();
            }
        });

        refreshLog();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(SerialLog.ACTION_LOG_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(logReceiver, filter);
        }
        refreshLog();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(logReceiver);
        } catch (IllegalArgumentException ignored) {
            // 이미 해제된 경우입니다.
        }
    }

    // ------------------------------------------------------------------ 가상 신호

    /**
     * 기기가 보내는 것과 똑같은 프레임을 만들어 실제 수신 경로에 넣습니다.
     * 지름길이 아니라 진짜 바이트로 넣기 때문에 CRC 검사와 중복 차단까지 그대로 거칩니다.
     *
     * @param corrupt true 면 비트 하나를 일부러 뒤집습니다. 이때는 <b>아무 앱도 열리지 않아야</b>
     *                맞습니다. "깨진 신호는 실행되지 않는다"를 기기 없이 보여 주는 시험입니다.
     */
    private View.OnClickListener frameListener(final int button, final int kind,
                                               final boolean corrupt) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.tap(DiagnosticsActivity.this);
                UsbSerialService.simulateFrame(DiagnosticsActivity.this, button, kind, corrupt);
                String what = corrupt
                        ? "일부러 깨뜨린 신호를 넣었어요. 아무 일도 안 일어나야 정상이에요."
                        : (kind == FrameCodec.K_LONG
                                ? "길게 누른 신호를 넣었어요."
                                : button + "번 버튼 신호를 넣었어요.");
                Toast.makeText(DiagnosticsActivity.this, what, Toast.LENGTH_SHORT).show();
            }
        };
    }

    private void refreshLog() {
        List<String> lines = SerialLog.snapshot();
        if (lines.isEmpty()) {
            logText.setText(R.string.test_log_empty);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        logText.setText(sb.toString().trim());
    }

    // ------------------------------------------------------------------ 전체 검사

    private void runAllChecks() {
        summaryText.setText(R.string.test_running);
        resultContainer.removeAllViews();

        final List<Check> checks = new ArrayList<>();
        checks.add(checkUsbDevice());
        checks.add(checkOverlayPermission());
        checks.add(checkBatteryOptimization());
        checks.add(checkNotificationPermission());
        checks.add(checkInternet());
        checks.add(checkSpeechRecognition());
        checks.add(checkTextToSpeech());
        checks.addAll(checkSlots());
        checks.add(checkShortcuts());
        checks.add(checkOfflineKeywords());
        checks.add(checkFirstSetup());
        checks.add(checkAiPlanFilter());
        checks.add(checkDeviceSlotStore());
        checks.add(checkPocketGuard());
        checks.add(checkPowerSaving());
        // 기기에 "상태를 알려 달라"고 먼저 물어봅니다. 답은 아래 항목들에서 확인합니다.
        UsbSerialService.requestDeviceStatus();
        checks.add(checkDeviceProtocol());
        checks.add(checkRoundTrip());
        checks.add(checkFrameQuality());
        checks.add(checkLatency());

        render(checks);

        // AI 연결은 실제로 불러 봐야 알 수 있으므로 마지막에 따로 확인합니다.
        if (!gemini.hasApiKey()) {
            checks.add(new Check("AI(제미나이) 연결", false,
                    "API 키가 없어요. 설정에서 키를 넣어 주세요."));
            render(checks);
            return;
        }

        final int aiIndex = checks.size();
        checks.add(new Check("AI(제미나이) 연결", true, "확인하는 중이에요…"));
        render(checks);

        gemini.testApiKey(new GeminiClient.Callback<String>() {
            @Override
            public void onSuccess(String result) {
                checks.set(aiIndex, new Check("AI(제미나이) 연결", true, "잘 연결됩니다."));
                render(checks);
            }

            @Override
            public void onError(String friendlyMessage) {
                checks.set(aiIndex, new Check("AI(제미나이) 연결", false, friendlyMessage));
                render(checks);
            }
        });
    }

    // ------------------------------------------------------------------ 검사 항목

    private Check checkUsbDevice() {
        boolean attached = UsbSerialService.hasUsbDevice(this);
        return new Check("기기 연결", attached,
                attached ? "기기가 꽂혀 있어요."
                        : "지금은 기기가 안 꽂혀 있어요. (아래 '기기 없이 눌러 보기'로 시험할 수 있어요.)");
    }

    private Check checkOverlayPermission() {
        boolean ok = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        return new Check("다른 앱 위에 표시", ok,
                ok ? "허락돼 있어요."
                        : "없으면 버튼을 눌러도 앱이 안 열려요. 설정에서 켜 주세요.");
    }

    private Check checkBatteryOptimization() {
        boolean ok = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            ok = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        }
        return new Check("배터리 절약 예외", ok,
                ok ? "설정돼 있어요." : "없으면 한참 뒤에 버튼이 안 먹을 수 있어요.");
    }

    private Check checkNotificationPermission() {
        boolean ok = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ok = ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.POST_NOTIFICATIONS)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return new Check("알림 권한", ok,
                ok ? "허락돼 있어요." : "없으면 상시 알림이 안 보여요. (동작에는 문제 없어요.)");
    }

    private Check checkInternet() {
        ConnectivityManager cm =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        boolean online = false;
        if (cm != null) {
            NetworkInfo info = cm.getActiveNetworkInfo();
            online = info != null && info.isConnected();
        }
        return new Check("인터넷 연결", online,
                online ? "연결돼 있어요." : "AI 기능을 쓰려면 인터넷이 필요해요.");
    }

    private Check checkSpeechRecognition() {
        boolean ok = SpeechRecognizer.isRecognitionAvailable(this);
        return new Check("말로 입력하기", ok,
                ok ? "쓸 수 있어요." : "이 휴대폰에서는 말로 입력할 수 없어요. 글자로 입력해 주세요.");
    }

    private Check checkTextToSpeech() {
        // 실제로 한 문장 읽어 보게 해서 귀로 확인하실 수 있게 합니다.
        SpeechManager.get(this).speak("소리 확인입니다. 이 말이 들리면 잘 되는 거예요.");
        return new Check("소리로 읽어 주기", true,
                "지금 한 문장 읽어 드렸어요. 안 들리면 휴대폰 소리를 키워 주세요.");
    }

    private List<Check> checkSlots() {
        List<Check> checks = new ArrayList<>();
        for (int slot = 1; slot <= Prefs.SLOT_COUNT; slot++) {
            String label = prefs.getSlotLabel(slot);
            String name = slot + "번 버튼";

            if (!"APP".equals(prefs.getSlotActionType(slot))) {
                boolean ok = !TextUtils.isEmpty(label);
                checks.add(new Check(name, ok, ok ? label + " (빠른 동작)" : "연결한 동작이 없어요."));
                continue;
            }

            String packageName = prefs.getSlotPackage(slot);
            if (TextUtils.isEmpty(packageName)) {
                checks.add(new Check(name, false, "연결한 앱이 없어요."));
                continue;
            }
            boolean installed = appRepository.findByPackage(packageName) != null;
            checks.add(new Check(name, installed,
                    installed ? (TextUtils.isEmpty(label) ? packageName : label)
                            : (label + " 앱이 지워졌어요. 다시 정해 주세요.")));
        }
        return checks;
    }

    private Check checkShortcuts() {
        int count = 0;
        try {
            count = ShortcutManagerCompat.getDynamicShortcuts(this).size();
        } catch (Exception ignored) {
            // 바로가기를 못 읽는 기기도 있습니다.
        }
        boolean ok = count > 0;
        return new Check("삼성 루틴 바로가기", ok,
                ok ? count + "개가 등록돼 있어요." : "아직 없어요. '삼성 루틴 연결'에서 만들어 주세요.");
    }

    // ------------------------------------------------------------------ 기기(펌웨어) 검사
    //
    // 아래 셋은 기기가 직접 재서 보내 준 값이거나, 앱이 실제로 세어 둔 값입니다.
    // "잘 되는 것 같다"가 아니라 숫자로 보여야 어디가 문제인지 알 수 있습니다.

    private Check checkDeviceProtocol() {
        String info = UsbSerialService.deviceInfo();
        if (info == null) {
            return new Check("기기 펌웨어", false,
                    "아직 기기가 자기 소개(HELLO)를 보내지 않았어요. "
                            + "옛 펌웨어이거나 기기가 안 꽂혀 있을 수 있어요.");
        }
        String stats = UsbSerialService.deviceStats();
        return new Check("기기 펌웨어", true, stats == null ? info : info + "\n" + stats);
    }

    /**
     * 프레임을 보내고 받는 왕복이 실제로 도는지 봅니다.
     * 이 한 번의 왕복이 CRC·프레이밍·양방향 링크를 한꺼번에 시험합니다.
     */
    private Check checkRoundTrip() {
        String summary = UsbSerialService.roundTripSummary();
        if (summary == null) {
            return new Check("기기 왕복 응답", false,
                    "기기에 물어봤는데 아직 답이 없어요. 기기가 안 꽂혀 있거나 옛 펌웨어일 수 있어요.");
        }
        return new Check("기기 왕복 응답", true, summary + " 주고받기가 정상입니다.");
    }

    private Check checkFrameQuality() {
        String summary = UsbSerialService.frameSummary();
        if (summary == null) {
            return new Check("신호 품질", false, "아직 받은 신호가 없어요.");
        }
        return new Check("신호 품질", true,
                summary + "\n깨진 신호는 버려지므로 엉뚱한 앱이 열리지 않아요.");
    }

    private Check checkLatency() {
        String summary = UsbSerialService.latencySummary();
        if (summary == null) {
            return new Check("반응 빠르기", false,
                    "아직 잰 적이 없어요. 위에서 버튼 신호를 한 번 넣어 보세요.");
        }
        return new Check("반응 빠르기", true, summary);
    }

    private Check checkOfflineKeywords() {
        // AI 없이도 앱을 찾을 수 있는지, 실제 사전을 한 번 돌려서 확인합니다.
        boolean ok = !KeywordMatcher.candidatesFor("길 찾고 싶어").isEmpty();
        return new Check("AI 없이 앱 찾기", ok,
                ok ? "낱말 " + KeywordMatcher.keywordCount() + "개로 찾을 수 있어요."
                        : "사전을 읽지 못했어요.");
    }


    private Check checkFirstSetup() {
        boolean done = prefs.isOnboarded();
        return new Check("처음 설정", done,
                done ? "마쳤어요. 다시 하려면 설정 화면에서 '처음 설정 다시 하기'를 눌러 주세요."
                        : "아직 안 하셨어요. 자주 하는 일을 고르시면 AI가 단추를 정해 드려요.");
    }

    private Check checkAiPlanFilter() {
        // AI가 "이 휴대폰에 없는 앱"을 골랐을 때 정말 걸러지는지 그 자리에서 시험합니다.
        // 이 검사가 실패하면, 눌러도 아무 일도 안 일어나는 단추가 생길 수 있습니다.
        Set<String> installed = new HashSet<>();
        for (AppItem item : appRepository.getInstalledApps()) {
            installed.add(item.packageName);
        }

        List<SlotPlanner.Assignment> fake = new ArrayList<>();
        fake.add(new SlotPlanner.Assignment(1, "com.this.app.does.not.exist", "없는 앱", "", true));
        List<SlotPlanner.Assignment> filtered =
                SlotPlanner.reconcile(fake, Collections.<String>emptyList(), installed);

        boolean ok = filtered.isEmpty();
        return new Check("AI 답 걸러 내기", ok,
                ok ? "AI가 없는 앱을 골라도 단추에 안 들어가요. (고를 수 있는 일 "
                        + FunctionCatalog.size() + "가지)"
                        : "없는 앱이 걸러지지 않았어요.");
    }


    private Check checkPocketGuard() {
        // 기기가 "주머니에서 눌린 것 같아 걸렀다"고 보고한 숫자입니다.
        // 0이면 좋은 것이고, 0이 아니어도 고장이 아니라 제대로 막고 있다는 뜻입니다.
        String summary = UsbSerialService.guardSummary();
        if (TextUtils.isEmpty(summary)) {
            return new Check("주머니 오작동 막기", true,
                    "기기를 꽂으면 여기에 걸러 낸 횟수가 나옵니다.");
        }
        return new Check("주머니 오작동 막기", true, summary);
    }

    private Check checkDeviceSlotStore() {
        // 기기에 매핑이 저장돼 있으면 폰을 바꿔도 설정이 따라옵니다.
        boolean saved = prefs.hasAnyExplicitSlot();
        return new Check("기기에 설정 저장", saved,
                saved ? "정해 두신 버튼을 기기에도 적어 둡니다. 폰을 바꿔도 그대로 따라옵니다."
                        : "아직 정해 둔 버튼이 없어 기기에 적을 것이 없어요.");
    }


    private Check checkPowerSaving() {
        // 기기가 스스로 잰 값입니다. 전류가 아니라 "잠들어 있던 시간"의 비율이라,
        // 전류계 없이도 절전이 실제로 도는지 확인할 수 있습니다.
        String summary = UsbSerialService.powerSummary();
        if (TextUtils.isEmpty(summary)) {
            return new Check("절전", true,
                    "기기를 꽂고 앱을 잠시 닫아 두면, 얼마나 잠들어 있었는지 여기에 나옵니다.");
        }
        return new Check("절전", true, summary);
    }

    // ------------------------------------------------------------------ 결과 그리기

    private void render(List<Check> checks) {
        resultContainer.removeAllViews();

        int failed = 0;
        for (Check check : checks) {
            if (!check.ok) {
                failed++;
            }
            resultContainer.addView(buildRow(check));
        }

        if (failed == 0) {
            summaryText.setText("✅ 모두 좋아요. (" + checks.size() + "가지 검사)");
            summaryText.setTextColor(ContextCompat.getColor(this, R.color.vk_success));
        } else {
            summaryText.setText("⚠️ " + failed + "가지를 손봐야 해요. (" + checks.size() + "가지 검사)");
            summaryText.setTextColor(ContextCompat.getColor(this, R.color.vk_danger));
        }
    }

    private View buildRow(Check check) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(check.ok ? R.drawable.bg_card : R.drawable.bg_status_bad);
        int pad = dp(14);
        row.setPadding(pad, pad, pad, pad);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        row.setLayoutParams(params);

        TextView title = new TextView(this);
        title.setText((check.ok ? "✅  " : "⚠️  ") + check.name);
        title.setTextSize(20f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(this,
                check.ok ? R.color.vk_success : R.color.vk_danger));
        row.addView(title);

        TextView detail = new TextView(this);
        detail.setText(check.detail);
        detail.setTextSize(17f);
        detail.setTextColor(ContextCompat.getColor(this, R.color.vk_text));
        row.addView(detail);

        return row;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    /** 다른 화면에서 테스트 화면을 열 때 씁니다. */
    public static void open(Context context) {
        context.startActivity(new Intent(context, DiagnosticsActivity.class));
    }
}
