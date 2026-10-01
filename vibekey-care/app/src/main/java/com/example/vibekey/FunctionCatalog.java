package com.example.vibekey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 첫 실행 화면에서 어르신이 고르실 "자주 하는 일" 목록입니다.
 *
 * 앱 이름(카카오톡·네이버지도…)이 아니라 <b>하고 싶은 일</b>(전화 걸기·길 찾기)로 적어 두었습니다.
 * 어르신은 앱 이름을 모르셔도 "무엇을 하고 싶은지"는 아시기 때문입니다.
 * 고르신 일을 실제 앱으로 바꾸는 일은 AI가 하고, AI를 못 쓸 때는 아래 candidates 로 대신합니다.
 *
 * 안드로이드 클래스를 쓰지 않는 순수 자바라서 단위 테스트로 바로 검증할 수 있습니다.
 * (app/src/test/java/com/example/vibekey/FunctionCatalogTest.java)
 */
public final class FunctionCatalog {

    /** 고를 수 있는 "일" 하나. */
    public static final class Function {
        /** 저장·전달에 쓰는 영문 아이디 */
        public final String id;
        /** 화면에 크게 보여 드릴 한국어 이름 */
        public final String label;
        /** 그림(아이콘)을 고를 때 쓰는 이름. 화면 코드에서 그림으로 바꿉니다. */
        public final String iconName;
        /** AI를 못 쓸 때 대신 쓰는 후보 앱들. 앞쪽이 우선입니다. */
        public final String[] candidates;

        Function(String id, String label, String iconName, String[] candidates) {
            this.id = id;
            this.label = label;
            this.iconName = iconName;
            this.candidates = candidates;
        }
    }

    private static final List<Function> ALL = new ArrayList<>();
    private static final Map<String, Function> BY_ID = new LinkedHashMap<>();

    private static void add(String id, String label, String icon, String... candidates) {
        Function f = new Function(id, label, icon, candidates);
        ALL.add(f);
        BY_ID.put(id, f);
    }

    static {
        // 어르신이 실제로 가장 자주 쓰시는 순서로 두었습니다. (위쪽이 먼저 눈에 들어옵니다)
        add("call", "전화 걸기", "ic_phone",
                "com.samsung.android.dialer", "com.google.android.dialer", "com.android.dialer");
        add("kakao", "카카오톡", "ic_chat",
                "com.kakao.talk");
        add("map", "길 찾기", "ic_map",
                "com.nhn.android.nmap", "com.kakao.map", "com.google.android.apps.maps");
        add("photo", "사진 보기", "ic_photo",
                "com.sec.android.gallery3d", "com.google.android.apps.photos");
        add("camera", "사진 찍기", "ic_camera",
                "com.sec.android.app.camera", "com.google.android.GoogleCamera");
        add("message", "문자 보내기", "ic_message",
                "com.samsung.android.messaging", "com.google.android.apps.messaging");
        add("video", "영상 보기", "ic_video",
                "com.google.android.youtube");
        add("alarm", "약 먹을 시간 알림", "ic_alarm",
                "com.sec.android.app.clockpackage", "com.google.android.deskclock");
        add("bank", "은행 · 송금", "ic_bank",
                "viva.republica.toss", "com.kbstar.kbbank", "com.shinhan.sbanking",
                "com.kakaobank.channel");
        add("bus", "버스 · 지하철", "ic_bus",
                "com.kakao.map", "com.nhn.android.nmap");
        add("weather", "날씨 보기", "ic_weather",
                "com.sec.android.daemonapp", "com.google.android.googlequicksearchbox");
        add("internet", "인터넷 찾아보기", "ic_globe",
                "com.nhn.android.search", "com.sec.android.app.sbrowser", "com.android.chrome");
        add("music", "음악 듣기", "ic_music",
                "com.google.android.youtube.music", "com.iloen.melon");
        add("health", "걸음 수 · 건강", "ic_health",
                "com.sec.android.app.shealth", "com.google.android.apps.fitness");
    }

    private FunctionCatalog() {
    }

    /** 화면에 보여 줄 순서 그대로의 전체 목록입니다. */
    public static List<Function> all() {
        return Collections.unmodifiableList(ALL);
    }

    /** 아이디로 하나를 찾습니다. 없으면 null 입니다. */
    public static Function byId(String id) {
        return id == null ? null : BY_ID.get(id);
    }

    /** 아이디를 한국어 이름으로 바꿉니다. 모르는 아이디면 아이디를 그대로 돌려 줍니다. */
    public static String labelOf(String id) {
        Function f = byId(id);
        return f == null ? String.valueOf(id) : f.label;
    }

    /** 고르신 아이디들을 "전화 걸기, 길 찾기" 처럼 이어 붙입니다. (AI에게 보낼 때 씁니다) */
    public static String labelsOf(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(labelOf(id));
        }
        return sb.toString();
    }

    public static int size() {
        return ALL.size();
    }
}
