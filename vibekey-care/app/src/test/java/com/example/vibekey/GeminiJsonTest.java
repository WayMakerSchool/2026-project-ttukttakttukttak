package com.example.vibekey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

/**
 * 제미나이가 돌려준 글에서 JSON을 꺼내는 부분을 검사합니다.
 * 모델은 형식을 지키라고 해도 ```json 으로 감싸거나 앞뒤에 말을 붙이는 일이 잦아,
 * 이 부분이 약하면 AI 답이 통째로 날아갑니다.
 */
public class GeminiJsonTest {

    @Test
    public void 순수한_JSON을_읽는다() {
        JSONObject json = GeminiClient.parseLooseJson("{\"answer\":\"안녕하세요\"}");
        assertNotNull(json);
        assertEquals("안녕하세요", json.optString("answer"));
    }

    @Test
    public void 코드블록으로_감싼_JSON을_읽는다() {
        String raw = "```json\n{\"answer\":\"약을 드실 시간이에요\",\"openPackage\":\"\"}\n```";
        JSONObject json = GeminiClient.parseLooseJson(raw);
        assertNotNull(json);
        assertEquals("약을 드실 시간이에요", json.optString("answer"));
    }

    @Test
    public void 앞뒤에_설명이_붙어도_JSON만_꺼낸다() {
        String raw = "네, 알겠습니다.\n{\"answer\":\"지도를 열게요\",\"openPackage\":\"com.kakao.map\"}\n필요하시면 말씀하세요.";
        JSONObject json = GeminiClient.parseLooseJson(raw);
        assertNotNull(json);
        assertEquals("com.kakao.map", json.optString("openPackage"));
    }

    @Test
    public void 중첩된_JSON도_끝까지_읽는다() {
        String raw = "{\"suggestions\":[{\"slot\":1,\"packageName\":\"com.kakao.talk\"}]}";
        JSONObject json = GeminiClient.parseLooseJson(raw);
        assertNotNull(json);
        assertNotNull(json.optJSONArray("suggestions"));
        assertEquals(1, json.optJSONArray("suggestions").length());
    }

    @Test
    public void JSON이_없으면_null을_돌려준다() {
        assertNull(GeminiClient.parseLooseJson("죄송해요. 잘 모르겠어요."));
        assertNull(GeminiClient.parseLooseJson(""));
        assertNull(GeminiClient.parseLooseJson("   "));
        assertNull(GeminiClient.parseLooseJson(null));
    }

    @Test
    public void 망가진_JSON은_null을_돌려준다() {
        assertNull(GeminiClient.parseLooseJson("{\"answer\": "));
    }

    // ------------------------------------------------------------ 첫 실행 키 매핑 답 읽기

    @Test
    public void 단추_배치_답을_읽는다() {
        JSONObject json = GeminiClient.parseLooseJson(
                "```json\n{\"suggestions\":["
                        + "{\"slot\":1,\"packageName\":\"com.kakao.talk\",\"label\":\"카카오톡\",\"reason\":\"매일 쓰셔서요\"},"
                        + "{\"slot\":2,\"packageName\":\"com.samsung.android.dialer\",\"label\":\"전화\",\"reason\":\"급할 때요\"}"
                        + "]}\n```");
        List<GeminiClient.SlotSuggestion> list = GeminiClient.parseSuggestions(json);

        assertEquals(2, list.size());
        assertEquals(1, list.get(0).slot);
        assertEquals("com.kakao.talk", list.get(0).packageName);
        assertEquals("카카오톡", list.get(0).label);
        assertEquals("매일 쓰셔서요", list.get(0).reason);
        assertEquals(2, list.get(1).slot);
    }

    @Test
    public void 패키지명이_빈_항목은_버린다() {
        JSONObject json = GeminiClient.parseLooseJson(
                "{\"suggestions\":[{\"slot\":1,\"packageName\":\"\"},"
                        + "{\"slot\":2,\"packageName\":\"  \"},"
                        + "{\"slot\":3,\"packageName\":\"com.kakao.talk\"}]}");
        List<GeminiClient.SlotSuggestion> list = GeminiClient.parseSuggestions(json);

        assertEquals(1, list.size());
        assertEquals(3, list.get(0).slot);
    }

    @Test
    public void 단추_번호를_빠뜨리면_순서대로_매긴다() {
        JSONObject json = GeminiClient.parseLooseJson(
                "{\"suggestions\":[{\"packageName\":\"com.kakao.talk\"},"
                        + "{\"packageName\":\"com.samsung.android.dialer\"}]}");
        List<GeminiClient.SlotSuggestion> list = GeminiClient.parseSuggestions(json);

        assertEquals(1, list.get(0).slot);
        assertEquals(2, list.get(1).slot);
    }

    @Test
    public void 배치_목록이_없어도_터지지_않는다() {
        assertTrue(GeminiClient.parseSuggestions(null).isEmpty());
        assertTrue(GeminiClient.parseSuggestions(
                GeminiClient.parseLooseJson("{\"answer\":\"모르겠어요\"}")).isEmpty());
    }
}
