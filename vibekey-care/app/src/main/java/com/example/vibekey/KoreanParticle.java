package com.example.vibekey;

/**
 * 앞말의 받침에 따라 한국어 조사를 골라 줍니다. ("을/를", "이/가", "은/는", "으로/로")
 *
 * <p><b>왜 필요한가</b><br>
 * 이 앱은 화면에 쓴 글을 그대로 소리로도 읽어 드립니다.
 * 그래서 "카카오톡을(를) 엽니다"라고 적어 두면 어르신 귀에는
 * "카카오톡을 괄호 를 괄호 엽니다"로 들립니다. 조사를 하나로 정해야 합니다.
 *
 * <p><b>한글이 아닌 이름</b><br>
 * 앱 이름이 영어("Phone")나 숫자로 끝날 때는 한국 사람이 읽는 소리를 기준으로 어림합니다.
 * 예를 들어 끝의 e는 소리가 나지 않으므로 "Phone"은 'ㄴ'으로 끝난다고 봅니다.
 * 완벽하지는 않지만 "을(를)"처럼 읽히는 것보다는 훨씬 자연스럽습니다.
 *
 * <p>안드로이드 클래스를 쓰지 않는 순수 자바라서 단위 테스트로 바로 검증할 수 있습니다.
 * (app/src/test/java/com/example/vibekey/KoreanParticleTest.java)
 */
public final class KoreanParticle {

    private static final char HANGUL_FIRST = 0xAC00; // '가'
    private static final char HANGUL_LAST = 0xD7A3;  // '힣'

    /** 숫자를 한국어로 읽었을 때 받침이 있는지: 0영 1일 2이 3삼 4사 5오 6육 7칠 8팔 9구 */
    private static final boolean[] DIGIT_HAS_FINAL = {
            true,  // 0 영
            true,  // 1 일
            false, // 2 이
            true,  // 3 삼
            false, // 4 사
            false, // 5 오
            true,  // 6 육
            true,  // 7 칠
            true,  // 8 팔
            false  // 9 구
    };

    /** 영어 낱말이 이 글자로 끝나면 한국어로 읽을 때 받침이 생깁니다. (ㄹ ㅁ ㄴ ㅇ) */
    private static final String LATIN_WITH_FINAL = "lmng";

    private KoreanParticle() {
    }

    /** "카카오톡" → "을", "지도" → "를" */
    public static String eulReul(String word) {
        return hasFinalConsonant(word) ? "을" : "를";
    }

    /** "카카오톡" → "이", "지도" → "가" */
    public static String iGa(String word) {
        return hasFinalConsonant(word) ? "이" : "가";
    }

    /** "카카오톡" → "은", "지도" → "는" */
    public static String eunNeun(String word) {
        return hasFinalConsonant(word) ? "은" : "는";
    }

    /**
     * "병원" → "으로", "지도" → "로".
     * 'ㄹ' 받침은 예외라서 "서울로"처럼 "로"를 씁니다.
     */
    public static String euroRo(String word) {
        char last = lastMeaningfulChar(word);
        if (isHangulSyllable(last) && (last - HANGUL_FIRST) % 28 == 8) {
            return "로"; // 'ㄹ' 받침
        }
        return hasFinalConsonant(word) ? "으로" : "로";
    }

    /** 앞말에 조사를 바로 붙여 줍니다. ("카카오톡" + 을/를 → "카카오톡을") */
    public static String withEulReul(String word) {
        return word + eulReul(word);
    }

    public static String withIGa(String word) {
        return word + iGa(word);
    }

    public static String withEunNeun(String word) {
        return word + eunNeun(word);
    }

    /**
     * 마지막 글자에 받침이 있는지 봅니다.
     * 빈 글이거나 알 수 없는 글자면 받침이 없다고 봅니다. ("를/가/는")
     */
    public static boolean hasFinalConsonant(String word) {
        char last = lastMeaningfulChar(word);
        if (last == 0) {
            return false;
        }
        if (isHangulSyllable(last)) {
            return (last - HANGUL_FIRST) % 28 != 0;
        }
        if (last >= '0' && last <= '9') {
            return DIGIT_HAS_FINAL[last - '0'];
        }
        char lower = Character.toLowerCase(last);
        if (lower >= 'a' && lower <= 'z') {
            return LATIN_WITH_FINAL.indexOf(lower) >= 0;
        }
        return false;
    }

    /**
     * 조사를 정할 때 기준이 되는 마지막 글자를 찾습니다.
     * 괄호나 따옴표 같은 기호는 건너뛰고, 영어 낱말 끝의 소리 없는 e도 건너뜁니다.
     */
    private static char lastMeaningfulChar(String word) {
        if (word == null) {
            return 0;
        }
        String trimmed = word.trim();
        int index = trimmed.length() - 1;

        // 뒤쪽의 기호(괄호·따옴표·마침표 등)는 건너뜁니다.
        while (index >= 0 && !isCountable(trimmed.charAt(index))) {
            index--;
        }
        if (index < 0) {
            return 0;
        }

        // 영어 낱말 끝의 소리 없는 e: "Phone" 은 'ㄴ' 으로 끝난다고 봅니다.
        char last = trimmed.charAt(index);
        if ((last == 'e' || last == 'E') && index >= 1) {
            char before = Character.toLowerCase(trimmed.charAt(index - 1));
            if (before >= 'a' && before <= 'z' && "aeiou".indexOf(before) < 0) {
                return before;
            }
        }
        return last;
    }

    private static boolean isCountable(char c) {
        return isHangulSyllable(c) || Character.isLetterOrDigit(c);
    }

    private static boolean isHangulSyllable(char c) {
        return c >= HANGUL_FIRST && c <= HANGUL_LAST;
    }
}
