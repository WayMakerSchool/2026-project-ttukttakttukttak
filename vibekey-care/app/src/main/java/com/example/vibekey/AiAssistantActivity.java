package com.example.vibekey;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.WindowManager;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 말로 물어보고 소리로 답을 듣는 AI 도우미 화면입니다. (제미나이 API 사용)
 *
 * 어르신을 배려한 점
 *  · 화면에서 누를 곳은 가운데 큰 마이크 하나뿐입니다.
 *  · 답은 큰 글씨로 보여 주고 동시에 소리로도 읽어 드립니다.
 *  · "○○ 앱을 열까요?" 처럼 다음에 할 일을 먼저 물어보고 실행합니다.
 */
public class AiAssistantActivity extends BaseActivity {

    public static final String EXTRA_AUTO_LISTEN = "auto_listen";
    public static final String EXTRA_QUESTION = "question";

    private TextView questionText;
    private TextView answerText;
    private TextView statusText;
    private TextView micLabel;
    private ImageView micButton;

    private GeminiClient gemini;
    private AppRepository appRepository;
    private Prefs prefs;

    private boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showOverLockScreen();
        setContentView(R.layout.activity_ai);

        gemini = new GeminiClient(this);
        appRepository = new AppRepository(this);
        prefs = Prefs.with(this);

        questionText = findViewById(R.id.questionText);
        answerText = findViewById(R.id.answerText);
        statusText = findViewById(R.id.aiStatusText);
        micLabel = findViewById(R.id.micLabel);
        micButton = findViewById(R.id.btnMic);

        micButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.tap(AiAssistantActivity.this);
                listen();
            }
        });

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SpeechManager.get(AiAssistantActivity.this).stop();
                finish();
            }
        });

        MaterialButton repeat = findViewById(R.id.btnRepeat);
        repeat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SpeechManager speech = SpeechManager.get(AiAssistantActivity.this);
                if (speech.isSpeaking()) {
                    speech.stop();
                    return;
                }
                String last = answerText.getText().toString();
                if (!TextUtils.isEmpty(last)) {
                    speech.speak(last);
                }
            }
        });

        findViewById(R.id.btnType).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTypeDialog();
            }
        });

        // 지난 답이 있으면 다시 보여 드립니다.
        String lastAnswer = prefs.getLastAiAnswer();
        if (!TextUtils.isEmpty(lastAnswer)) {
            answerText.setText(lastAnswer);
        }

        handleLaunchIntent(getIntent());
    }

    /**
     * 잠금화면 위에서도 도우미가 보이게 합니다.
     *
     * <p>단추를 길게 누르는 순간에도 폰은 대개 잠겨 있습니다. 이건 우리 앱 화면이라
     * 남의 앱과 달리 스스로 잠금화면 위에 올라올 수 있습니다. 다만 잠금을 풀지는
     * 않습니다 — 도우미에게 말을 걸고 답을 듣는 것까지만 잠금화면에서 되고,
     * 도우미가 다른 앱을 열어야 할 때는 그때 잠금 해제를 묻습니다.
     */
    private void showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunchIntent(intent);
    }

    private void handleLaunchIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String question = intent.getStringExtra(EXTRA_QUESTION);
        if (!TextUtils.isEmpty(question)) {
            ask(question);
            return;
        }
        if (intent.getBooleanExtra(EXTRA_AUTO_LISTEN, false)) {
            // 화면이 뜨자마자 바로 들을 준비를 합니다.
            micButton.postDelayed(new Runnable() {
                @Override
                public void run() {
                    listen();
                }
            }, 350);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        SpeechManager.get(this).stop();
    }

    // ------------------------------------------------------------------ 듣기

    private void listen() {
        if (busy) {
            return;
        }
        if (!gemini.hasApiKey()) {
            showNeedApiKey();
            return;
        }
        setListeningUi(true);
        startVoiceInput("무엇을 도와 드릴까요?", new VoiceResultListener() {
            @Override
            public void onVoiceResult(String spokenText) {
                setListeningUi(false);
                if (TextUtils.isEmpty(spokenText)) {
                    statusText.setText("잘 못 들었어요. 다시 한 번 눌러 주세요.");
                    return;
                }
                ask(spokenText);
            }
        });
    }

    private void showTypeDialog() {
        final EditText input = new EditText(this);
        input.setHint("궁금한 것을 적어 주세요");
        input.setTextSize(20f);
        input.setMinHeight((int) (72 * getResources().getDisplayMetrics().density));
        input.setTextColor(ContextCompat.getColor(this, R.color.vk_text));

        LinearLayout wrapper = new LinearLayout(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        wrapper.setPadding(pad, pad, pad, 0);
        wrapper.addView(input);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ai_type)
                .setView(wrapper)
                .setPositiveButton("물어보기", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String text = input.getText().toString().trim();
                        if (!TextUtils.isEmpty(text)) {
                            ask(text);
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ 물어보기

    private void ask(final String question) {
        if (!gemini.hasApiKey()) {
            showNeedApiKey();
            return;
        }
        busy = true;
        questionText.setVisibility(View.VISIBLE);
        questionText.setText("내가 한 말: " + question);
        answerText.setText(R.string.ai_thinking);
        statusText.setText(R.string.ai_thinking);
        micLabel.setText(R.string.ai_thinking);

        gemini.askAssistant(question, appRepository.buildAppCatalogForPrompt(120),
                new GeminiClient.Callback<GeminiClient.AssistantReply>() {
                    @Override
                    public void onSuccess(GeminiClient.AssistantReply reply) {
                        busy = false;
                        micLabel.setText(R.string.ai_speak);
                        statusText.setText("");
                        showAnswer(reply);
                    }

                    @Override
                    public void onError(String friendlyMessage) {
                        busy = false;
                        micLabel.setText(R.string.ai_speak);
                        statusText.setText("");
                        answerText.setText(friendlyMessage);
                        SpeechManager.get(AiAssistantActivity.this).speak(friendlyMessage);
                    }
                });
    }

    private void showAnswer(GeminiClient.AssistantReply reply) {
        answerText.setText(reply.answer);
        prefs.setLastAiAnswer(reply.answer);
        SpeechManager.get(this).speak(reply.answer);

        if (!TextUtils.isEmpty(reply.action)) {
            confirmAction(reply);
            return;
        }

        if (TextUtils.isEmpty(reply.openPackage)) {
            return;
        }
        final AppItem item = appRepository.findByPackage(reply.openPackage);
        if (item == null) {
            return; // AI가 없는 앱을 골랐다면 그냥 답만 보여 드립니다.
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("앱을 열까요?")
                .setMessage("'" + item.label + "'" + KoreanParticle.eulReul(item.label) + " 지금 열어 드릴까요?")
                .setPositiveButton("네, 열어 주세요", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        SpeechManager.get(AiAssistantActivity.this).stop();
                        AppLauncher.launchPackage(AiAssistantActivity.this, item.packageName);
                    }
                })
                .setNegativeButton("아니요", null)
                .show();
    }

    /** AI가 전화·문자·길찾기를 하겠다고 골랐을 때, 실제로 실행하기 전에 한 번 더 확인합니다. */
    private void confirmAction(final GeminiClient.AssistantReply reply) {
        String title;
        String message;
        switch (reply.action) {
            case "dial":
                title = "전화를 걸까요?";
                message = reply.actionNumber + " 으로 전화 걸 준비를 할까요?";
                break;
            case "sms":
                title = "문자를 보낼까요?";
                message = reply.actionNumber + " 으로 문자 보낼 준비를 할까요?";
                break;
            case "maps":
                title = "길을 찾을까요?";
                message = "'" + reply.actionText + "'(으)로 가는 길을 찾아 드릴까요?";
                break;
            default:
                return;
        }
        if ("maps".equals(reply.action) && TextUtils.isEmpty(reply.actionText)) {
            return;
        }
        if (("dial".equals(reply.action) || "sms".equals(reply.action))
                && TextUtils.isEmpty(reply.actionNumber)) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("네", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        SpeechManager.get(AiAssistantActivity.this).stop();
                        runAction(reply);
                    }
                })
                .setNegativeButton("아니요", null)
                .show();
    }

    private void runAction(GeminiClient.AssistantReply reply) {
        boolean ok;
        switch (reply.action) {
            case "dial":
                ok = IntentActions.dial(this, reply.actionNumber);
                break;
            case "sms":
                ok = IntentActions.sms(this, reply.actionNumber, reply.actionText);
                break;
            case "maps":
                ok = IntentActions.navigate(this, reply.actionText);
                break;
            default:
                ok = false;
        }
        if (!ok) {
            Toast.makeText(this, "그 동작을 실행하지 못했어요.", Toast.LENGTH_LONG).show();
        }
    }

    private void showNeedApiKey() {
        answerText.setText(R.string.ai_no_key);
        new MaterialAlertDialogBuilder(this)
                .setTitle("AI 준비가 필요해요")
                .setMessage(getString(R.string.ai_no_key))
                .setPositiveButton("설정 열기", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        startActivity(new Intent(AiAssistantActivity.this, SettingsActivity.class));
                    }
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    // ------------------------------------------------------------------ 화면 상태

    private void setListeningUi(boolean listening) {
        if (listening) {
            micButton.setBackgroundResource(R.drawable.bg_mic_active);
            micLabel.setText(R.string.ai_listening);
            statusText.setText(R.string.ai_listening);
        } else {
            micButton.setBackgroundResource(R.drawable.bg_mic);
            micLabel.setText(R.string.ai_speak);
            statusText.setText("");
        }
    }
}
