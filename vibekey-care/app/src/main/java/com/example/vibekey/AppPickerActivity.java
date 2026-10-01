package com.example.vibekey;

import android.content.DialogInterface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 버튼에 연결할 앱을 고르는 화면입니다.
 *
 * 어르신을 배려한 점
 *  · "말로 찾기"가 화면 맨 위에 가장 크게 있습니다.
 *    (제미나이가 "길 찾고 싶어" 같은 말을 알아듣고 알맞은 앱을 골라 줍니다.)
 *  · AI를 쓸 수 없을 때에도 한국어 키워드 사전으로 찾아 드립니다.
 *  · 목록의 한 줄 높이를 크게 하고 아이콘도 크게 그립니다.
 */
public class AppPickerActivity extends BaseActivity {

    public static final String EXTRA_SLOT = "slot";

    private int slot = 1;
    private AppRepository appRepository;
    private GeminiClient gemini;

    private final List<AppItem> visibleApps = new ArrayList<>();
    private AppAdapter adapter;
    private TextView emptyText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_picker);

        slot = getIntent().getIntExtra(EXTRA_SLOT, 1);
        appRepository = new AppRepository(this);
        gemini = new GeminiClient(this);

        TextView title = findViewById(R.id.pickerTitle);
        title.setText(slot + "번 버튼에 넣을 앱");

        emptyText = findViewById(R.id.emptyText);

        RecyclerView list = findViewById(R.id.appList);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AppAdapter();
        list.setAdapter(adapter);

        visibleApps.addAll(appRepository.getInstalledApps());
        adapter.notifyDataSetChanged();

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        findViewById(R.id.btnVoiceSearch).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.tap(AppPickerActivity.this);
                searchByVoice();
            }
        });

        findViewById(R.id.btnQuickDial).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.tap(AppPickerActivity.this);
                showQuickActionDialog("DIAL");
            }
        });
        findViewById(R.id.btnQuickSms).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.tap(AppPickerActivity.this);
                showQuickActionDialog("SMS");
            }
        });
        findViewById(R.id.btnQuickMaps).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.tap(AppPickerActivity.this);
                showQuickActionDialog("MAPS");
            }
        });

        EditText searchInput = findViewById(R.id.searchInput);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilter(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        SpeechManager.get(this).speakIfEnabled(this,
                slot + "번 버튼에 넣을 앱을 골라 주세요. 말로 찾기를 누르셔도 됩니다.");
    }

    // ------------------------------------------------------------------ 찾기

    private void applyFilter(String query) {
        visibleApps.clear();
        visibleApps.addAll(appRepository.filterByText(query));
        adapter.notifyDataSetChanged();
        emptyText.setVisibility(visibleApps.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void searchByVoice() {
        startVoiceInput("무엇을 하고 싶으세요?", new VoiceResultListener() {
            @Override
            public void onVoiceResult(String spokenText) {
                if (TextUtils.isEmpty(spokenText)) {
                    Toast.makeText(AppPickerActivity.this,
                            "잘 못 들었어요. 다시 눌러 주세요.", Toast.LENGTH_LONG).show();
                    return;
                }
                findAppFor(spokenText);
            }
        });
    }

    /** 말한 내용으로 앱을 찾습니다. AI를 쓸 수 있으면 AI에게, 아니면 키워드 사전으로 찾습니다. */
    private void findAppFor(final String sentence) {
        if (!gemini.hasApiKey()) {
            AppItem guess = appRepository.guessByNaturalLanguage(sentence);
            handleSearchResult(sentence, guess, false);
            return;
        }

        final AlertDialog waiting = new MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.picker_ai_working))
                .setCancelable(false)
                .show();

        gemini.matchApp(sentence, appRepository.buildAppCatalogForPrompt(150),
                new GeminiClient.Callback<String>() {
                    @Override
                    public void onSuccess(String packageName) {
                        dismiss(waiting);
                        AppItem found = appRepository.findByPackage(packageName);
                        if (found == null) {
                            found = appRepository.guessByNaturalLanguage(sentence);
                        }
                        handleSearchResult(sentence, found, true);
                    }

                    @Override
                    public void onError(String friendlyMessage) {
                        dismiss(waiting);
                        // AI가 안 되면 오프라인 사전으로 다시 시도합니다.
                        AppItem guess = appRepository.guessByNaturalLanguage(sentence);
                        if (guess == null) {
                            Toast.makeText(AppPickerActivity.this, friendlyMessage,
                                    Toast.LENGTH_LONG).show();
                        }
                        handleSearchResult(sentence, guess, false);
                    }
                });
    }

    private void handleSearchResult(String sentence, final AppItem found, boolean byAi) {
        if (found == null) {
            // 못 찾으면 말한 내용으로 목록만 걸러 드립니다.
            EditText searchInput = findViewById(R.id.searchInput);
            searchInput.setText(sentence);
            String message = "알맞은 앱을 찾지 못했어요. 아래 목록에서 직접 골라 주세요.";
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            SpeechManager.get(this).speakIfEnabled(this, message);
            return;
        }

        String question = (byAi ? "AI가 고른 앱이에요.\n\n" : "")
                + "'" + found.label + "'" + KoreanParticle.eulReul(found.label) + " " + slot + "번 버튼에 넣을까요?";
        new MaterialAlertDialogBuilder(this)
                .setTitle("이 앱이 맞나요?")
                .setMessage(question)
                .setPositiveButton("네, 넣어 주세요", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        select(found);
                    }
                })
                .setNegativeButton("아니요, 직접 고를래요", null)
                .show();
        SpeechManager.get(this).speakIfEnabled(this, question);
    }

    // ------------------------------------------------------------------ 빠른 동작 (앱 대신 바로 실행)

    /** 전화 걸기 · 문자 보내기 · 길찾기를 버튼에 넣기 위한 값(라벨·번호·내용)을 입력받습니다. */
    private void showQuickActionDialog(final String type) {
        int pad = (int) (20 * getResources().getDisplayMetrics().density);

        final EditText labelInput = new EditText(this);
        labelInput.setHint("무엇으로 부를까요? (예: " + defaultLabelHint(type) + ")");
        labelInput.setTextSize(20f);
        labelInput.setMinHeight((int) (56 * getResources().getDisplayMetrics().density));
        labelInput.setTextColor(ContextCompat.getColor(this, R.color.vk_text));

        final EditText valueInput = new EditText(this);
        valueInput.setHint("MAPS".equals(type) ? "목적지 (예: 서울역)" : "전화번호");
        valueInput.setTextSize(20f);
        valueInput.setMinHeight((int) (56 * getResources().getDisplayMetrics().density));
        valueInput.setTextColor(ContextCompat.getColor(this, R.color.vk_text));
        if ("DIAL".equals(type) || "SMS".equals(type)) {
            valueInput.setInputType(InputType.TYPE_CLASS_PHONE);
        }

        final EditText textInput = new EditText(this);
        textInput.setHint("보낼 말 (예: 도착하면 전화 주세요)");
        textInput.setTextSize(20f);
        textInput.setMinHeight((int) (56 * getResources().getDisplayMetrics().density));
        textInput.setTextColor(ContextCompat.getColor(this, R.color.vk_text));

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(pad, pad, pad, 0);
        wrapper.addView(labelInput);
        addSpacer(wrapper, pad / 2);
        wrapper.addView(valueInput);
        if ("SMS".equals(type)) {
            addSpacer(wrapper, pad / 2);
            wrapper.addView(textInput);
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(quickActionTitle(type))
                .setView(wrapper)
                .setPositiveButton("넣기", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String label = labelInput.getText().toString().trim();
                        String value = valueInput.getText().toString().trim();
                        String text = textInput.getText().toString().trim();
                        saveQuickAction(type, label, value, text);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void addSpacer(LinearLayout wrapper, int height) {
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height));
        wrapper.addView(spacer);
    }

    private String defaultLabelHint(String type) {
        return "MAPS".equals(type) ? "회사" : "아들";
    }

    private String quickActionTitle(String type) {
        switch (type) {
            case "DIAL":
                return "전화 걸기 넣기";
            case "SMS":
                return "문자 보내기 넣기";
            default:
                return "길찾기 넣기";
        }
    }

    private void saveQuickAction(String type, String label, String value, String text) {
        if (TextUtils.isEmpty(label) || TextUtils.isEmpty(value)) {
            Toast.makeText(this, "이름과 " + ("MAPS".equals(type) ? "목적지" : "번호") + "를 모두 넣어 주세요.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        String number = "MAPS".equals(type) ? "" : value;
        String actionText = "MAPS".equals(type) ? value : text;

        Prefs.with(this).setSlotAction(slot, type, label, number, actionText);
        RoutineBridge.refreshShortcuts(this);
        UsbSerialService.pushSlotMap(this);   // 기기 플래시에도 같은 값을 적어 둡니다
        Haptics.success(this);

        String message = slot + "번 버튼에 '" + label + "'" + KoreanParticle.eulReul(label) + " 넣었어요.";
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        SpeechManager.get(this).speakIfEnabled(this, message);
        finish();
    }

    // ------------------------------------------------------------------ 고르기

    private void select(AppItem item) {
        Prefs.with(this).setSlot(slot, item.packageName, item.label);
        RoutineBridge.refreshShortcuts(this);
        UsbSerialService.pushSlotMap(this);   // 기기 플래시에도 같은 값을 적어 둡니다
        Haptics.success(this);

        String message = slot + "번 버튼에 " + KoreanParticle.withEulReul(item.label) + " 넣었어요.";
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        SpeechManager.get(this).speakIfEnabled(this, message);
        finish();
    }

    private void dismiss(AlertDialog dialog) {
        if (dialog != null && dialog.isShowing() && !isFinishing()) {
            dialog.dismiss();
        }
    }

    // ------------------------------------------------------------------ 목록

    private class AppAdapter extends RecyclerView.Adapter<AppHolder> {

        @NonNull
        @Override
        public AppHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_app, parent, false);
            return new AppHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull AppHolder holder, int position) {
            final AppItem item = visibleApps.get(position);
            holder.name.setText(item.label);
            holder.icon.setImageDrawable(item.icon);
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Haptics.tap(AppPickerActivity.this);
                    select(item);
                }
            });
        }

        @Override
        public int getItemCount() {
            return visibleApps.size();
        }
    }

    private static class AppHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;

        AppHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.appIcon);
            name = itemView.findViewById(R.id.appName);
        }
    }
}
