package com.example.vibekey;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 처음 켰을 때 딱 한 번 나오는 설정 화면입니다.
 *
 * <p><b>흐름</b>
 * <pre>
 *   자주 하는 일 고르기  →  AI가 단추 1·2·3번을 정함  →  왜 그렇게 정했는지 보여 드리고 확인
 * </pre>
 *
 * <p><b>왜 이렇게 만들었나</b><br>
 * 어르신은 "카카오톡"이라는 앱 이름은 몰라도 "아들과 이야기하고 싶다"는 것은 아십니다.
 * 그래서 앱을 고르시게 하지 않고 <b>하고 싶은 일</b>만 고르시게 한 다음,
 * 그 일을 실제로 할 수 있는 앱을 AI가 찾아 단추에 연결합니다.
 *
 * <p><b>AI가 틀려도 안전한 이유</b><br>
 * AI가 준 답은 그대로 쓰지 않고 {@link SlotPlanner#reconcile} 로 한 번 걸러 냅니다.
 * 이 휴대폰에 없는 앱, 겹치는 앱, 엉뚱한 단추 번호는 여기서 모두 버려지고
 * 빈자리는 키워드 사전으로 채웁니다. 인터넷이 끊겨 있어도 설정은 끝까지 마칠 수 있습니다.
 */
public class OnboardingActivity extends BaseActivity {

    /** 설정 화면에서 "처음 설정 다시 하기"로 들어왔는지 */
    public static final String EXTRA_REDO = "redo";

    private static final int MAX_PICK = Prefs.SLOT_COUNT;

    private ViewFlipper flipper;
    private GridLayout functionGrid;
    private TextView pickCount;
    private TextView resultNote;
    private LinearLayout resultList;

    private Prefs prefs;
    private AppRepository appRepository;
    private GeminiClient gemini;

    /** 고르신 순서를 지켜야 1번 단추부터 채울 수 있어서 순서 있는 자료구조를 씁니다. */
    private final Map<String, View> cards = new LinkedHashMap<>();
    private final List<String> selected = new ArrayList<>();

    /** 지금 화면에 보여 주고 있는 배치 (확인을 누르면 이대로 저장합니다) */
    private List<SlotPlanner.Assignment> currentPlan = new ArrayList<>();

    public static void open(Context context, boolean redo) {
        context.startActivity(new Intent(context, OnboardingActivity.class)
                .putExtra(EXTRA_REDO, redo));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);

        prefs = Prefs.with(this);
        appRepository = new AppRepository(this);
        gemini = new GeminiClient(this);

        flipper = findViewById(R.id.flipper);
        functionGrid = findViewById(R.id.functionGrid);
        pickCount = findViewById(R.id.pickCount);
        resultNote = findViewById(R.id.resultNote);
        resultList = findViewById(R.id.resultList);

        buildFunctionGrid();
        updatePickCount();

        findViewById(R.id.btnOnboardNext).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.tap(OnboardingActivity.this);
                askAiToMapKeys();
            }
        });

        findViewById(R.id.btnOnboardSkip).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishOnboarding(false);
            }
        });

        findViewById(R.id.btnOnboardApply).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.tap(OnboardingActivity.this);
                applyPlan();
            }
        });

        findViewById(R.id.btnOnboardRetry).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.tap(OnboardingActivity.this);
                showPickPage();
            }
        });

        SpeechManager.get(this).speakIfEnabled(this,
                getString(R.string.onboard_title) + " " + getString(R.string.onboard_subtitle));
    }

    // ------------------------------------------------------------------ 첫 장: 고르기

    /** "자주 하는 일" 칸들을 두 줄씩 채워 넣습니다. */
    private void buildFunctionGrid() {
        LayoutInflater inflater = LayoutInflater.from(this);
        int margin = getResources().getDimensionPixelSize(R.dimen.space_xs);
        int index = 0;

        for (final FunctionCatalog.Function function : FunctionCatalog.all()) {
            View card = inflater.inflate(R.layout.item_function, functionGrid, false);

            ImageView icon = card.findViewById(R.id.functionIcon);
            TextView name = card.findViewById(R.id.functionName);
            icon.setImageResource(iconResFor(function.iconName));
            name.setText(function.label);
            card.setContentDescription(function.label);

            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = GridLayout.LayoutParams.WRAP_CONTENT;
            params.columnSpec = GridLayout.spec(index % 2, 1, 1f);
            params.rowSpec = GridLayout.spec(index / 2, 1);
            params.setMargins(margin, margin, margin, margin);
            card.setLayoutParams(params);

            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggle(function.id);
                }
            });

            functionGrid.addView(card);
            cards.put(function.id, card);
            index++;
        }
    }

    /** 칸을 눌렀을 때 고르거나 취소합니다. 단추가 3개뿐이라 3가지까지만 받습니다. */
    private void toggle(String functionId) {
        Haptics.tap(this);
        if (selected.contains(functionId)) {
            selected.remove(functionId);
        } else {
            if (selected.size() >= MAX_PICK) {
                // 맨 먼저 고르신 것을 빼고 새로 고른 것을 넣습니다.
                // ("3개까지만 됩니다"라고 막아 세우면 어르신이 당황하시기 때문입니다.)
                String dropped = selected.remove(0);
                paint(dropped, false);
            }
            selected.add(functionId);
            SpeechManager.get(this).speakIfEnabled(this, FunctionCatalog.labelOf(functionId));
        }
        paint(functionId, selected.contains(functionId));
        updatePickCount();
    }

    /** 고른 칸은 테두리를 굵게 하고 동그란 체크를 붙입니다. */
    private void paint(String functionId, boolean isSelected) {
        View card = cards.get(functionId);
        if (card == null) {
            return;
        }
        card.setBackgroundResource(isSelected ? R.drawable.bg_card_selected : R.drawable.bg_card);
        card.findViewById(R.id.functionCheck)
                .setVisibility(isSelected ? View.VISIBLE : View.GONE);
        ImageView icon = card.findViewById(R.id.functionIcon);
        icon.setColorFilter(ContextCompat.getColor(this,
                isSelected ? R.color.vk_primary : R.color.vk_text_secondary));
        TextView name = card.findViewById(R.id.functionName);
        name.setTextColor(ContextCompat.getColor(this,
                isSelected ? R.color.vk_primary_dark : R.color.vk_text));
    }

    private void updatePickCount() {
        pickCount.setText(selected.isEmpty()
                ? getString(R.string.onboard_need_pick)
                : getString(R.string.onboard_picked_count, selected.size()));

        // 단추는 늘 눌리게 두고 색만 흐리게 합니다.
        // 눌러도 아무 반응이 없는 단추는 어르신께 "고장 났다"로 읽히기 때문입니다.
        // 아직 안 고르신 채로 누르시면 아래 askAiToMapKeys 가 말로 알려 드립니다.
        findViewById(R.id.btnOnboardNext).setAlpha(selected.isEmpty() ? 0.45f : 1f);
    }

    private void showPickPage() {
        flipper.setDisplayedChild(0);
    }

    // ------------------------------------------------------------------ AI 키 매핑

    /** 고르신 일을 AI에게 넘겨 단추 1·2·3번을 정하게 합니다. */
    private void askAiToMapKeys() {
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.onboard_need_pick, Toast.LENGTH_LONG).show();
            SpeechManager.get(this).speakIfEnabled(this, getString(R.string.onboard_need_pick));
            Haptics.tap(this);
            return;
        }

        final Set<String> installed = installedPackages();

        if (!gemini.hasApiKey()) {
            // 키가 없어도 설정은 끝까지 마칠 수 있어야 합니다.
            showPlan(SlotPlanner.reconcile(null, selected, installed), false);
            return;
        }

        final AlertDialog waiting = new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.onboard_working)
                .setCancelable(false)
                .show();
        SpeechManager.get(this).speakIfEnabled(this, getString(R.string.onboard_working));

        gemini.assignSlotsForFunctions(
                FunctionCatalog.labelsOf(selected),
                appRepository.buildAppCatalogForPrompt(120),
                new GeminiClient.Callback<List<GeminiClient.SlotSuggestion>>() {
                    @Override
                    public void onSuccess(List<GeminiClient.SlotSuggestion> suggestions) {
                        dismiss(waiting);
                        List<SlotPlanner.Assignment> proposal = new ArrayList<>();
                        for (GeminiClient.SlotSuggestion s : suggestions) {
                            proposal.add(new SlotPlanner.Assignment(
                                    s.slot, s.packageName, s.label, s.reason, true));
                        }
                        List<SlotPlanner.Assignment> plan =
                                SlotPlanner.reconcile(proposal, selected, installed);
                        showPlan(plan, SlotPlanner.countFromAi(plan) > 0);
                    }

                    @Override
                    public void onError(String friendlyMessage) {
                        dismiss(waiting);
                        // AI가 안 되면 사전으로 대신 정해 드립니다. 설정이 멈추지 않게 합니다.
                        showPlan(SlotPlanner.reconcile(null, selected, installed), false);
                    }
                });
    }

    private Set<String> installedPackages() {
        Set<String> packages = new HashSet<>();
        for (AppItem item : appRepository.getInstalledApps()) {
            packages.add(item.packageName);
        }
        return packages;
    }

    // ------------------------------------------------------------------ 둘째 장: 결과 확인

    /** AI가 정한 결과를 단추 순서대로 크게 보여 드립니다. */
    private void showPlan(List<SlotPlanner.Assignment> plan, boolean fromAi) {
        currentPlan = plan == null ? new ArrayList<SlotPlanner.Assignment>() : plan;
        resultList.removeAllViews();

        if (currentPlan.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.onboard_empty);
            empty.setTextSize(21);
            empty.setTextColor(ContextCompat.getColor(this, R.color.vk_text));
            resultList.addView(empty);
            resultNote.setText(R.string.onboard_result_offline);
            flipper.setDisplayedChild(1);
            SpeechManager.get(this).speakIfEnabled(this, getString(R.string.onboard_empty));
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        StringBuilder spoken = new StringBuilder();

        for (SlotPlanner.Assignment a : currentPlan) {
            View row = inflater.inflate(R.layout.item_result_slot, resultList, false);

            AppItem item = appRepository.findByPackage(a.packageName);
            String appName = item != null ? item.label
                    : (TextUtils.isEmpty(a.label) ? a.packageName : a.label);

            row.findViewById(R.id.resultBar)
                    .setBackgroundColor(ContextCompat.getColor(this, slotColor(a.slot)));

            ImageView icon = row.findViewById(R.id.resultIcon);
            if (item != null && item.icon != null) {
                icon.setImageDrawable(item.icon);
            } else {
                icon.setImageResource(R.drawable.ic_apps);
            }

            TextView slotText = row.findViewById(R.id.resultSlot);
            slotText.setText(getString(R.string.onboard_result_slot, a.slot));
            slotText.setTextColor(ContextCompat.getColor(this, slotColor(a.slot)));

            ((TextView) row.findViewById(R.id.resultApp)).setText(appName);

            TextView reason = row.findViewById(R.id.resultReason);
            if (TextUtils.isEmpty(a.reason)) {
                reason.setVisibility(View.GONE);
            } else {
                reason.setText(a.reason);
            }

            resultList.addView(row);
            spoken.append(a.slot).append("번 단추는 ").append(appName).append(". ");
        }

        resultNote.setText(fromAi ? R.string.onboard_result_ai : R.string.onboard_result_offline);
        flipper.setDisplayedChild(1);
        Haptics.success(this);
        SpeechManager.get(this).speakIfEnabled(this,
                getString(R.string.onboard_result_title) + " " + spoken);
    }

    private static int slotColor(int slot) {
        switch (slot) {
            case 2:
                return R.color.vk_slot_2;
            case 3:
                return R.color.vk_slot_3;
            default:
                return R.color.vk_slot_1;
        }
    }

    /** "이대로 쓸게요" — 화면에 보여 드린 그대로 저장합니다. */
    private void applyPlan() {
        for (SlotPlanner.Assignment a : currentPlan) {
            AppItem item = appRepository.findByPackage(a.packageName);
            String label = item != null ? item.label
                    : (TextUtils.isEmpty(a.label) ? a.packageName : a.label);
            prefs.setSlot(a.slot, a.packageName, label);
        }
        finishOnboarding(true);
    }

    private void finishOnboarding(boolean applied) {
        prefs.setOnboarded(true);
        RoutineBridge.refreshShortcuts(this);
        UsbSerialService.pushSlotMap(this);   // 기기 플래시에도 적어 둬 새 폰에서 되찾을 수 있게
        if (applied) {
            Toast.makeText(this, R.string.onboard_done, Toast.LENGTH_LONG).show();
            SpeechManager.get(this).speakIfEnabled(this, getString(R.string.onboard_done));
        }
        finish();
    }

    // ------------------------------------------------------------------ 그림 고르기

    /**
     * {@link FunctionCatalog} 의 그림 이름을 실제 그림으로 바꿉니다.
     * (FunctionCatalog 는 단위 테스트를 위해 안드로이드 자원을 참조하지 않습니다.)
     */
    private static int iconResFor(String iconName) {
        if (iconName == null) {
            return R.drawable.ic_apps;
        }
        switch (iconName) {
            case "ic_phone":
                return R.drawable.ic_phone;
            case "ic_chat":
                return R.drawable.ic_chat;
            case "ic_message":
                return R.drawable.ic_message;
            case "ic_map":
                return R.drawable.ic_map;
            case "ic_photo":
                return R.drawable.ic_photo;
            case "ic_camera":
                return R.drawable.ic_camera;
            case "ic_video":
                return R.drawable.ic_video;
            case "ic_alarm":
                return R.drawable.ic_alarm;
            case "ic_bank":
                return R.drawable.ic_bank;
            case "ic_bus":
                return R.drawable.ic_bus;
            case "ic_weather":
                return R.drawable.ic_weather;
            case "ic_globe":
                return R.drawable.ic_globe;
            case "ic_music":
                return R.drawable.ic_music;
            case "ic_health":
                return R.drawable.ic_health;
            default:
                return R.drawable.ic_apps;
        }
    }

    // ------------------------------------------------------------------ 뒤로 가기

    @Override
    public void onBackPressed() {
        if (flipper != null && flipper.getDisplayedChild() == 1) {
            showPickPage(); // 결과 화면에서는 고르기로 되돌아갑니다.
            return;
        }
        super.onBackPressed();
    }

    private void dismiss(AlertDialog dialog) {
        if (dialog != null && dialog.isShowing() && !isFinishing()) {
            dialog.dismiss();
        }
    }
}
