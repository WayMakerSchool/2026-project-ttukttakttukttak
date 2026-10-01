package com.example.vibekey;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 구글 제미나이(Gemini) API를 호출하는 클라이언트입니다.
 *
 * - 별도 라이브러리 없이 HttpURLConnection 만 사용하므로 의존성이 늘지 않습니다.
 * - 모든 요청은 백그라운드 스레드에서 처리하고 결과는 메인 스레드로 돌려 줍니다.
 * - 어르신 사용자를 위해 "쉬운 말, 짧은 문장"으로 답하도록 시스템 지시문을 고정했습니다.
 */
public class GeminiClient {

    private static final String TAG = "VibeKeyGemini";

    /** 빠른 응답이 중요하므로 가벼운 flash 모델을 사용합니다. */
    private static final String MODEL = "gemini-2.5-flash";
    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent";

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 25000;

    private static final String PERSONA =
            "당신은 어르신을 도와 드리는 한국어 음성 비서 '바이브키 도우미'입니다.\n"
                    + "지켜야 할 규칙:\n"
                    + "1. 항상 존댓말을 쓰고, 아주 쉬운 낱말만 사용합니다.\n"
                    + "2. 한 문장은 짧게 쓰고, 전체 답은 3문장을 넘기지 않습니다.\n"
                    + "3. 어려운 외래어, 전문 용어, 영어 약자는 쓰지 않습니다.\n"
                    + "4. 목록이 필요하면 '첫째,' '둘째,' 처럼 말로 풀어서 설명합니다.\n"
                    + "5. 확실하지 않으면 아는 척하지 말고 모른다고 말씀드립니다.\n"
                    + "6. 응급 상황(가슴 통증, 호흡 곤란, 큰 낙상 등)으로 보이면 먼저 119에 전화하시라고 안내합니다.\n"
                    + "7. 답은 화면에 크게 보여 주고 소리로도 읽어 드리므로, 소리 내어 읽기 좋은 문장으로 씁니다.";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Prefs prefs;

    public GeminiClient(Context context) {
        this.prefs = Prefs.with(context);
    }

    // ------------------------------------------------------------------ 콜백

    public interface Callback<T> {
        void onSuccess(T result);

        void onError(String friendlyMessage);
    }

    /**
     * 도우미의 답변.
     * openPackage 가 있으면 그 앱을 열어 달라는 뜻이고,
     * action 이 있으면(dial/sms/maps) 앱을 여는 대신 그 동작을 바로 실행해 달라는 뜻입니다.
     */
    public static class AssistantReply {
        public String answer = "";
        public String openPackage = "";
        public String openLabel = "";
        /** "" · "dial" · "sms" · "maps" */
        public String action = "";
        public String actionNumber = "";
        public String actionText = "";
    }

    /** 버튼 자동 추천 결과 */
    public static class SlotSuggestion {
        public int slot;
        public String packageName = "";
        public String label = "";
        public String reason = "";
    }

    public boolean hasApiKey() {
        return prefs.hasGeminiApiKey();
    }

    // ------------------------------------------------------------------ 기능 1: 대화형 도우미

    /**
     * 어르신의 질문에 답하고, 필요하면 열어야 할 앱까지 골라 줍니다.
     */
    public void askAssistant(final String question,
                             final String appCatalog,
                             final Callback<AssistantReply> callback) {
        String system = PERSONA + "\n\n"
                + "휴대폰에 설치된 앱 목록입니다 (형식: 패키지명 | 앱 이름):\n"
                + appCatalog + "\n"
                + "사용자가 어떤 앱을 열어 달라고 하거나, 앱을 열어 드리는 것이 도움이 될 때에는\n"
                + "openPackage 에 위 목록에 실제로 있는 패키지명을 정확히 그대로 적으세요.\n"
                + "앱을 열 필요가 없으면 openPackage 는 빈 문자열로 두세요.\n\n"
                + "다음 세 가지는 앱을 여는 대신 바로 실행할 수 있는 동작입니다. 해당되면 action 에 적으세요.\n"
                + "1. action=\"dial\" — 사용자가 말한 문장에 전화번호 숫자가 그대로 들어 있고, 그 번호로 전화를 걸어 달라는 뜻일 때.\n"
                + "   actionNumber 에 그 번호를 적으세요.\n"
                + "2. action=\"sms\" — 문장에 전화번호 숫자가 들어 있고, 문자를 보내 달라는 뜻일 때.\n"
                + "   actionNumber 에 번호를, actionText 에 보낼 내용을 적으세요.\n"
                + "3. action=\"maps\" — 어떤 장소로 가는 길을 찾아 달라는 뜻일 때. actionText 에 그 장소 이름을 적으세요.\n"
                + "중요: 사람 이름(예: '아들', '엄마')만 말하고 실제 전화번호 숫자를 말하지 않았으면,\n"
                + "그 사람의 번호를 알 수 없으니 절대로 번호를 지어내지 말고 action 은 빈 문자열로 둔 채\n"
                + "answer 에서 번호를 알려 주시면 도와드리겠다고 답하세요.\n"
                + "위 세 동작에 해당하지 않으면 action, actionNumber, actionText 는 모두 빈 문자열로 두세요.\n\n"
                + "지금 시각은 " + currentTimeDescription() + " 입니다.\n\n"
                + "반드시 아래 형식의 JSON 하나만 출력하세요.\n"
                + "{\"answer\":\"어르신께 드릴 말씀\",\"openPackage\":\"\",\"openLabel\":\"\","
                + "\"action\":\"\",\"actionNumber\":\"\",\"actionText\":\"\"}";

        generateJson(system, question, new Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject json) {
                AssistantReply reply = new AssistantReply();
                reply.answer = json.optString("answer", "").trim();
                reply.openPackage = json.optString("openPackage", "").trim();
                reply.openLabel = json.optString("openLabel", "").trim();
                reply.action = json.optString("action", "").trim().toLowerCase();
                reply.actionNumber = json.optString("actionNumber", "").trim();
                reply.actionText = json.optString("actionText", "").trim();
                if (TextUtils.isEmpty(reply.answer)) {
                    reply.answer = "죄송해요. 잘 알아듣지 못했어요. 다시 한 번 말씀해 주세요.";
                }
                callback.onSuccess(reply);
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    // ------------------------------------------------------------------ 기능 2: 말로 앱 찾기

    /**
     * "길 찾고 싶어" 같은 자연스러운 말에서 알맞은 앱의 패키지명을 골라 냅니다.
     * 알맞은 앱이 없으면 빈 문자열을 돌려 줍니다.
     */
    public void matchApp(final String request,
                         final String appCatalog,
                         final Callback<String> callback) {
        String system = "당신은 한국 어르신이 하신 말씀을 듣고, 그 목적에 가장 알맞은 휴대폰 앱을 하나 고르는 도우미입니다.\n"
                + "아래는 이 휴대폰에 설치된 앱 목록입니다 (형식: 패키지명 | 앱 이름):\n"
                + appCatalog + "\n"
                + "규칙:\n"
                + "1. 반드시 위 목록에 있는 패키지명만 고릅니다. 목록에 없는 것은 절대 만들어 내지 않습니다.\n"
                + "2. 알맞은 앱이 하나도 없으면 packageName 을 빈 문자열로 둡니다.\n"
                + "3. reason 은 어르신께 보여 드릴 아주 쉬운 한 문장으로 씁니다.\n\n"
                + "반드시 아래 형식의 JSON 하나만 출력하세요.\n"
                + "{\"packageName\":\"\",\"label\":\"\",\"reason\":\"\"}";

        generateJson(system, request, new Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject json) {
                callback.onSuccess(json.optString("packageName", "").trim());
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    // ------------------------------------------------------------------ 기능 3: 버튼 자동 추천

    /**
     * 설치된 앱과 지금 시간대를 보고 1~3번 버튼에 어떤 앱을 넣으면 좋을지 추천합니다.
     */
    public void recommendSlots(final String appCatalog,
                               final Callback<List<SlotSuggestion>> callback) {
        String system = "당신은 어르신용 하드웨어 버튼 3개에 각각 어떤 앱을 연결하면 좋을지 추천하는 도우미입니다.\n"
                + "아래는 이 휴대폰에 설치된 앱 목록입니다 (형식: 패키지명 | 앱 이름):\n"
                + appCatalog + "\n"
                + "규칙:\n"
                + "1. 반드시 위 목록에 있는 패키지명만 고릅니다.\n"
                + "2. 어르신이 자주, 급하게 쓰는 기능(전화, 길찾기, 메시지, 사진, 알람 등)을 우선합니다.\n"
                + "3. 서로 다른 앱 3개를 고릅니다.\n"
                + "4. reason 은 '왜 이 앱인지'를 쉬운 한 문장으로 씁니다.\n\n"
                + "반드시 아래 형식의 JSON 하나만 출력하세요.\n"
                + "{\"suggestions\":[{\"slot\":1,\"packageName\":\"\",\"label\":\"\",\"reason\":\"\"},"
                + "{\"slot\":2,\"packageName\":\"\",\"label\":\"\",\"reason\":\"\"},"
                + "{\"slot\":3,\"packageName\":\"\",\"label\":\"\",\"reason\":\"\"}]}";

        String user = "지금은 " + currentTimeDescription() + " 입니다. 버튼 3개에 넣을 앱을 추천해 주세요.";

        generateJson(system, user, new Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject json) {
                callback.onSuccess(parseSuggestions(json));
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    // ------------------------------------------------------------------ 기능 4: 첫 실행 키 매핑

    /**
     * 첫 실행에서 어르신이 고르신 "자주 하는 일"을 보고, 기기 단추 1·2·3번에
     * 어떤 앱을 넣을지 AI가 직접 정해 줍니다.
     *
     * <p>여기서 나온 답은 그대로 쓰지 않고 {@link SlotPlanner#reconcile}로 한 번 걸러 냅니다.
     * AI가 이 휴대폰에 없는 앱을 고르더라도 어르신 화면에는 올라가지 않습니다.
     *
     * @param chosenFunctions 고르신 일들의 한국어 이름 (예: "전화 걸기, 길 찾기")
     * @param appCatalog      "패키지명 | 앱이름" 목록
     */
    public void assignSlotsForFunctions(final String chosenFunctions,
                                        final String appCatalog,
                                        final Callback<List<SlotSuggestion>> callback) {
        String system = "당신은 어르신용 하드웨어 단추 3개에 앱을 배치해 드리는 도우미입니다.\n"
                + "어르신이 '내가 자주 하는 일'을 직접 고르셨습니다. 그 일을 실제로 할 수 있는 앱을\n"
                + "이 휴대폰에서 찾아 단추에 하나씩 연결해 주세요.\n\n"
                + "이 휴대폰에 설치된 앱 목록입니다 (형식: 패키지명 | 앱 이름):\n"
                + appCatalog + "\n"
                + "규칙:\n"
                + "1. 반드시 위 목록에 있는 패키지명만 그대로 적습니다. 목록에 없는 것은 절대 지어내지 않습니다.\n"
                + "2. 단추는 1번, 2번, 3번 세 개뿐입니다. slot 에는 1·2·3 만 씁니다.\n"
                + "3. 어르신이 먼저 고르신 일일수록 앞 단추(1번)에 둡니다. 왼쪽 단추가 가장 누르기 쉽습니다.\n"
                + "4. 서로 다른 앱 3개를 고릅니다. 같은 앱을 두 단추에 넣지 않습니다.\n"
                + "5. 고르신 일이 3가지보다 적으면, 어르신이 자주 쓰실 만한 일(전화·길찾기 등)로 남은 단추를 채웁니다.\n"
                + "6. 고르신 일에 딱 맞는 앱이 없으면 가장 비슷한 일을 하는 앱을 고릅니다.\n"
                + "7. reason 은 '왜 이 단추에 이 앱인지'를 어르신께 드리는 아주 쉬운 한 문장으로 씁니다.\n"
                + "   존댓말을 쓰고, 어려운 외래어는 쓰지 않으며, 25자를 넘기지 않습니다.\n\n"
                + "반드시 아래 형식의 JSON 하나만 출력하세요.\n"
                + "{\"suggestions\":[{\"slot\":1,\"packageName\":\"\",\"label\":\"\",\"reason\":\"\"},"
                + "{\"slot\":2,\"packageName\":\"\",\"label\":\"\",\"reason\":\"\"},"
                + "{\"slot\":3,\"packageName\":\"\",\"label\":\"\",\"reason\":\"\"}]}";

        String user = "제가 자주 하는 일은 이렇습니다: " + chosenFunctions + "\n"
                + "지금은 " + currentTimeDescription() + " 입니다. 단추 3개를 정해 주세요.";

        generateJson(system, user, new Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject json) {
                callback.onSuccess(parseSuggestions(json));
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    /**
     * {"suggestions":[...]} 모양의 답에서 배치 목록을 꺼냅니다.
     * 안드로이드 클래스를 쓰지 않아 단위 테스트로 바로 검증할 수 있습니다.
     * (app/src/test/java/com/example/vibekey/GeminiJsonTest.java)
     */
    static List<SlotSuggestion> parseSuggestions(JSONObject json) {
        List<SlotSuggestion> list = new ArrayList<>();
        if (json == null) {
            return list;
        }
        JSONArray array = json.optJSONArray("suggestions");
        if (array == null) {
            return list;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            SlotSuggestion s = new SlotSuggestion();
            s.slot = item.optInt("slot", i + 1);
            s.packageName = item.optString("packageName", "").trim();
            s.label = item.optString("label", "").trim();
            s.reason = item.optString("reason", "").trim();
            if (!s.packageName.isEmpty()) {
                list.add(s);
            }
        }
        return list;
    }

    // ------------------------------------------------------------------ 기능 5: 키 확인

    /** 설정 화면에서 "연결 확인하기"를 눌렀을 때 API 키가 실제로 동작하는지 검사합니다. */
    public void testApiKey(final Callback<String> callback) {
        generateText("한 문장으로만 답하세요.", "연결 확인", new Callback<String>() {
            @Override
            public void onSuccess(String result) {
                callback.onSuccess(TextUtils.isEmpty(result) ? "연결에 성공했어요." : result);
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    // ------------------------------------------------------------------ 내부 구현

    private void generateJson(final String systemPrompt,
                              final String userPrompt,
                              final Callback<JSONObject> callback) {
        generateText(systemPrompt, userPrompt, new Callback<String>() {
            @Override
            public void onSuccess(String raw) {
                JSONObject parsed = parseLooseJson(raw);
                if (parsed == null) {
                    // JSON 형식이 아니면 본문을 그대로 답변으로 사용합니다.
                    parsed = new JSONObject();
                    try {
                        parsed.put("answer", raw);
                    } catch (Exception ignored) {
                        // JSONObject.put 은 여기서 실패하지 않습니다.
                    }
                }
                callback.onSuccess(parsed);
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    private void generateText(final String systemPrompt,
                              final String userPrompt,
                              final Callback<String> callback) {
        final String apiKey = prefs.getGeminiApiKey();
        if (TextUtils.isEmpty(apiKey)) {
            postError(callback, "AI를 쓰려면 먼저 설정 화면에서 제미나이 API 키를 넣어 주세요.");
            return;
        }

        executor.execute(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection connection = null;
                try {
                    URL url = new URL(ENDPOINT + "?key=" + URLEncoder.encode(apiKey, "UTF-8"));
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("POST");
                    connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    connection.setReadTimeout(READ_TIMEOUT_MS);
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    connection.setDoOutput(true);

                    byte[] body = buildRequestBody(systemPrompt, userPrompt)
                            .toString().getBytes(StandardCharsets.UTF_8);
                    OutputStream out = connection.getOutputStream();
                    out.write(body);
                    out.flush();
                    out.close();

                    int status = connection.getResponseCode();
                    String responseText = readStream(status >= 200 && status < 300
                            ? connection.getInputStream() : connection.getErrorStream());

                    if (status < 200 || status >= 300) {
                        Log.w(TAG, "Gemini HTTP " + status + ": " + responseText);
                        postError(callback, friendlyHttpError(status, responseText));
                        return;
                    }

                    String text = extractText(responseText);
                    if (TextUtils.isEmpty(text)) {
                        postError(callback, "AI가 답을 만들지 못했어요. 잠시 뒤에 다시 해 주세요.");
                        return;
                    }
                    postSuccess(callback, text);

                } catch (IOException e) {
                    Log.e(TAG, "Gemini request failed", e);
                    postError(callback, "인터넷 연결을 확인해 주세요. 잠시 뒤에 다시 해 주세요.");
                } catch (Exception e) {
                    Log.e(TAG, "Gemini unexpected error", e);
                    postError(callback, "문제가 생겼어요. 잠시 뒤에 다시 해 주세요.");
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }
        });
    }

    private JSONObject buildRequestBody(String systemPrompt, String userPrompt) throws Exception {
        JSONObject root = new JSONObject();

        JSONObject systemPart = new JSONObject().put("text", systemPrompt);
        root.put("systemInstruction", new JSONObject()
                .put("parts", new JSONArray().put(systemPart)));

        JSONObject userContent = new JSONObject()
                .put("role", "user")
                .put("parts", new JSONArray().put(new JSONObject().put("text", userPrompt)));
        root.put("contents", new JSONArray().put(userContent));

        JSONObject generationConfig = new JSONObject()
                .put("temperature", 0.4)
                .put("maxOutputTokens", 1024)
                // 응답 속도가 중요한 기기이므로 추론(thinking) 예산은 쓰지 않습니다.
                .put("thinkingConfig", new JSONObject().put("thinkingBudget", 0));
        root.put("generationConfig", generationConfig);

        return root;
    }

    /** 응답 JSON에서 실제 글자만 뽑아 냅니다. */
    @Nullable
    private String extractText(String responseText) {
        try {
            JSONObject root = new JSONObject(responseText);
            JSONArray candidates = root.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) {
                return null;
            }
            JSONObject content = candidates.optJSONObject(0) == null
                    ? null : candidates.optJSONObject(0).optJSONObject("content");
            if (content == null) {
                return null;
            }
            JSONArray parts = content.optJSONArray("parts");
            if (parts == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part == null || part.optBoolean("thought", false)) {
                    continue;
                }
                sb.append(part.optString("text", ""));
            }
            return sb.toString().trim();
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse Gemini response", e);
            return null;
        }
    }

    /**
     * 모델이 ```json ... ``` 처럼 감싸거나 앞뒤에 말을 붙여 답해도 JSON을 꺼낼 수 있게 합니다.
     * 안드로이드 클래스를 쓰지 않아 단위 테스트로 바로 검증할 수 있습니다.
     * (app/src/test/java/com/example/vibekey/GeminiJsonTest.java)
     */
    @Nullable
    static JSONObject parseLooseJson(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String text = raw.trim();
        int fence = text.indexOf("```");
        if (fence >= 0) {
            int start = text.indexOf('\n', fence);
            int end = text.lastIndexOf("```");
            if (start > 0 && end > start) {
                text = text.substring(start + 1, end).trim();
            }
        }
        int first = text.indexOf('{');
        int last = text.lastIndexOf('}');
        if (first < 0 || last <= first) {
            return null;
        }
        try {
            return new JSONObject(text.substring(first, last + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private String friendlyHttpError(int status, String body) {
        if (status == 400 && body != null && body.contains("API key not valid")) {
            return "API 키가 올바르지 않아요. 설정에서 키를 다시 확인해 주세요.";
        }
        switch (status) {
            case 401:
            case 403:
                return "API 키가 올바르지 않거나 사용 권한이 없어요. 설정에서 키를 다시 넣어 주세요.";
            case 429:
                return "지금은 사용량이 많아요. 1분쯤 뒤에 다시 해 주세요.";
            case 500:
            case 503:
                return "AI 서버가 잠시 쉬고 있어요. 잠시 뒤에 다시 해 주세요.";
            default:
                return "AI와 연결하지 못했어요. (오류 " + status + ")";
        }
    }

    private static String readStream(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
        }
        reader.close();
        return sb.toString();
    }

    private <T> void postSuccess(final Callback<T> callback, final T value) {
        main.post(new Runnable() {
            @Override
            public void run() {
                callback.onSuccess(value);
            }
        });
    }

    private void postError(final Callback<?> callback, final String message) {
        main.post(new Runnable() {
            @Override
            public void run() {
                callback.onError(message);
            }
        });
    }

    @NonNull
    private static String currentTimeDescription() {
        Calendar now = Calendar.getInstance();
        int hour = now.get(Calendar.HOUR_OF_DAY);
        String part;
        if (hour < 6) {
            part = "새벽";
        } else if (hour < 12) {
            part = "아침";
        } else if (hour < 18) {
            part = "낮";
        } else {
            part = "저녁";
        }
        return (now.get(Calendar.MONTH) + 1) + "월 " + now.get(Calendar.DAY_OF_MONTH) + "일 "
                + part + " " + hour + "시";
    }
}
