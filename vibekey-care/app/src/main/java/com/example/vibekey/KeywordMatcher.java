package com.example.vibekey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI(제미나이)를 쓸 수 없을 때 쓰는 한국어 키워드 사전입니다.
 * "길 찾고 싶어" 처럼 말해도 어떤 앱을 뜻하는지 추측해 후보 패키지 목록을 돌려 줍니다.
 *
 * 안드로이드 클래스를 쓰지 않는 순수 자바 로직이라 단위 테스트로 바로 검증할 수 있습니다.
 * (app/src/test/java/com/example/vibekey/KeywordMatcherTest.java)
 */
public final class KeywordMatcher {

    private static final Map<String, String[]> TABLE = new LinkedHashMap<>();

    static {
        TABLE.put("전화", new String[]{"com.samsung.android.dialer", "com.google.android.dialer", "com.android.dialer"});
        TABLE.put("통화", new String[]{"com.samsung.android.dialer", "com.google.android.dialer", "com.android.dialer"});
        TABLE.put("문자", new String[]{"com.samsung.android.messaging", "com.google.android.apps.messaging"});
        TABLE.put("메시지", new String[]{"com.samsung.android.messaging", "com.google.android.apps.messaging"});
        TABLE.put("카톡", new String[]{"com.kakao.talk"});
        TABLE.put("카카오톡", new String[]{"com.kakao.talk"});
        TABLE.put("길", new String[]{"com.nhn.android.nmap", "com.kakao.map", "com.google.android.apps.maps"});
        TABLE.put("지도", new String[]{"com.nhn.android.nmap", "com.kakao.map", "com.google.android.apps.maps"});
        TABLE.put("내비", new String[]{"com.locnall.KimGiSa", "com.nhn.android.nmap", "com.google.android.apps.maps"});
        TABLE.put("버스", new String[]{"com.kakao.map", "com.nhn.android.nmap"});
        TABLE.put("지하철", new String[]{"com.imagedrome.jihachul", "com.kakao.map"});
        TABLE.put("사진", new String[]{"com.sec.android.gallery3d", "com.google.android.apps.photos"});
        TABLE.put("갤러리", new String[]{"com.sec.android.gallery3d", "com.google.android.apps.photos"});
        TABLE.put("카메라", new String[]{"com.sec.android.app.camera", "com.google.android.GoogleCamera"});
        TABLE.put("유튜브", new String[]{"com.google.android.youtube"});
        TABLE.put("영상", new String[]{"com.google.android.youtube"});
        TABLE.put("음악", new String[]{"com.google.android.youtube.music", "com.iloen.melon"});
        TABLE.put("날씨", new String[]{"com.sec.android.daemonapp", "com.google.android.googlequicksearchbox"});
        TABLE.put("은행", new String[]{"com.kbstar.kbbank", "com.shinhan.sbanking", "viva.republica.toss"});
        TABLE.put("송금", new String[]{"viva.republica.toss", "com.kakaobank.channel"});
        TABLE.put("돈", new String[]{"viva.republica.toss", "com.kakaobank.channel"});
        TABLE.put("병원", new String[]{"com.google.android.apps.maps", "com.nhn.android.nmap"});
        TABLE.put("약", new String[]{"com.google.android.deskclock", "com.sec.android.app.clockpackage"});
        TABLE.put("알람", new String[]{"com.sec.android.app.clockpackage", "com.google.android.deskclock"});
        TABLE.put("시계", new String[]{"com.sec.android.app.clockpackage", "com.google.android.deskclock"});
        TABLE.put("건강", new String[]{"com.sec.android.app.shealth", "com.google.android.apps.fitness"});
        TABLE.put("걸음", new String[]{"com.sec.android.app.shealth", "com.google.android.apps.fitness"});
        TABLE.put("검색", new String[]{"com.nhn.android.search", "com.google.android.googlequicksearchbox"});
        TABLE.put("네이버", new String[]{"com.nhn.android.search"});
        TABLE.put("인터넷", new String[]{"com.sec.android.app.sbrowser", "com.android.chrome"});
        TABLE.put("뉴스", new String[]{"com.nhn.android.search", "com.google.android.apps.magazines"});
    }

    private KeywordMatcher() {
    }

    /**
     * 말한 문장에서 찾아낸 후보 패키지명을, 사전에 적힌 우선순위대로 돌려 줍니다.
     * 짚이는 것이 없으면 빈 목록입니다.
     */
    public static List<String> candidatesFor(String sentence) {
        if (sentence == null || sentence.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String text = sentence.toLowerCase(Locale.KOREA);
        List<String> candidates = new ArrayList<>();
        for (Map.Entry<String, String[]> entry : TABLE.entrySet()) {
            if (!text.contains(entry.getKey())) {
                continue;
            }
            for (String candidate : entry.getValue()) {
                if (!candidates.contains(candidate)) {
                    candidates.add(candidate);
                }
            }
        }
        return candidates;
    }

    /** 사전에 등록된 낱말 수 (진단 화면에서 보여 줍니다.) */
    public static int keywordCount() {
        return TABLE.size();
    }
}
