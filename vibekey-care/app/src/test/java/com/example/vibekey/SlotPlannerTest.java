package com.example.vibekey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 첫 실행에서 AI가 정한 단추 배치를 걸러 내는 로직을 검증합니다.
 *
 * 여기서 막지 못하면 어르신 화면에 "눌러도 아무 일도 안 일어나는 단추"가 생기므로,
 * AI가 낼 수 있는 잘못된 답을 하나씩 일부러 넣어 보며 확인합니다.
 */
public class SlotPlannerTest {

    private static final String DIALER = "com.samsung.android.dialer";
    private static final String KAKAO = "com.kakao.talk";
    private static final String NAVER_MAP = "com.nhn.android.nmap";
    private static final String GALLERY = "com.sec.android.gallery3d";
    private static final String YOUTUBE = "com.google.android.youtube";

    private static Set<String> installed(String... packages) {
        return new HashSet<>(Arrays.asList(packages));
    }

    private static SlotPlanner.Assignment ai(int slot, String pkg) {
        return new SlotPlanner.Assignment(slot, pkg, "", "AI가 정함", true);
    }

    // ---------------------------------------------------------------- 오프라인 계획

    @Test
    public void 고른_순서대로_1번부터_채운다() {
        List<SlotPlanner.Assignment> plan = SlotPlanner.planOffline(
                Arrays.asList("call", "kakao", "map"),
                installed(DIALER, KAKAO, NAVER_MAP));

        assertEquals(3, plan.size());
        assertEquals(1, plan.get(0).slot);
        assertEquals(DIALER, plan.get(0).packageName);
        assertEquals(2, plan.get(1).slot);
        assertEquals(KAKAO, plan.get(1).packageName);
        assertEquals(3, plan.get(2).slot);
        assertEquals(NAVER_MAP, plan.get(2).packageName);
    }

    @Test
    public void 안_깔린_앱은_건너뛰고_다음_기능이_그_자리를_받는다() {
        // 카카오톡이 없는 휴대폰. "카카오톡"은 통째로 빠지고 길찾기가 2번을 받습니다.
        List<SlotPlanner.Assignment> plan = SlotPlanner.planOffline(
                Arrays.asList("call", "kakao", "map"),
                installed(DIALER, NAVER_MAP));

        assertEquals(2, plan.size());
        assertEquals(DIALER, plan.get(0).packageName);
        assertEquals(2, plan.get(1).slot);
        assertEquals(NAVER_MAP, plan.get(1).packageName);
    }

    @Test
    public void 단추는_세_개뿐이라_네_개를_고르셔도_세_개만_넣는다() {
        List<SlotPlanner.Assignment> plan = SlotPlanner.planOffline(
                Arrays.asList("call", "kakao", "map", "photo"),
                installed(DIALER, KAKAO, NAVER_MAP, GALLERY));

        assertEquals(Prefs.SLOT_COUNT, plan.size());
    }

    @Test
    public void 같은_앱을_쓰는_기능을_둘_고르셔도_한_번만_넣는다() {
        // "길 찾기"와 "버스·지하철"은 후보 앱이 겹칩니다.
        List<SlotPlanner.Assignment> plan = SlotPlanner.planOffline(
                Arrays.asList("map", "bus"),
                installed(NAVER_MAP));

        assertEquals(1, plan.size());
        assertEquals(NAVER_MAP, plan.get(0).packageName);
    }

    @Test
    public void 아무것도_안_고르면_빈_계획을_돌려_준다() {
        assertTrue(SlotPlanner.planOffline(
                Collections.<String>emptyList(), installed(DIALER)).isEmpty());
        assertTrue(SlotPlanner.planOffline(null, installed(DIALER)).isEmpty());
    }

    @Test
    public void 모르는_기능_아이디는_조용히_무시한다() {
        List<SlotPlanner.Assignment> plan = SlotPlanner.planOffline(
                Arrays.asList("없는기능", "call"),
                installed(DIALER));

        assertEquals(1, plan.size());
        assertEquals(1, plan.get(0).slot);
        assertEquals(DIALER, plan.get(0).packageName);
    }

    // ---------------------------------------------------------------- AI 답 검증

    @Test
    public void AI가_고른_그대로_받아들인다() {
        List<SlotPlanner.Assignment> plan = SlotPlanner.reconcile(
                Arrays.asList(ai(1, KAKAO), ai(2, DIALER), ai(3, NAVER_MAP)),
                Arrays.asList("kakao", "call", "map"),
                installed(KAKAO, DIALER, NAVER_MAP));

        assertEquals(3, plan.size());
        assertEquals(KAKAO, plan.get(0).packageName);
        assertEquals(DIALER, plan.get(1).packageName);
        assertEquals(NAVER_MAP, plan.get(2).packageName);
        assertEquals(3, SlotPlanner.countFromAi(plan));
    }

    @Test
    public void AI가_없는_앱을_지어내면_버리고_사전으로_채운다() {
        List<SlotPlanner.Assignment> plan = SlotPlanner.reconcile(
                Arrays.asList(ai(1, "com.존재하지.않는앱"), ai(2, DIALER)),
                Arrays.asList("call", "map"),
                installed(DIALER, NAVER_MAP));

        // 지어낸 앱은 사라지고, 남은 1번 자리는 사전이 채웁니다.
        for (SlotPlanner.Assignment a : plan) {
            assertTrue(installedContains(a.packageName));
        }
        assertEquals(2, plan.size());
        assertEquals(1, plan.get(0).slot);
        assertEquals(NAVER_MAP, plan.get(0).packageName);
        assertFalse(plan.get(0).fromAi);
        assertEquals(2, plan.get(1).slot);
        assertEquals(DIALER, plan.get(1).packageName);
        assertTrue(plan.get(1).fromAi);
    }

    @Test
    public void AI가_같은_앱을_두_단추에_넣으면_하나만_남긴다() {
        List<SlotPlanner.Assignment> plan = SlotPlanner.reconcile(
                Arrays.asList(ai(1, KAKAO), ai(2, KAKAO)),
                Collections.<String>emptyList(),
                installed(KAKAO));

        assertEquals(1, plan.size());
        assertEquals(1, plan.get(0).slot);
    }

    @Test
    public void AI가_없는_단추_번호를_적으면_버린다() {
        List<SlotPlanner.Assignment> plan = SlotPlanner.reconcile(
                Arrays.asList(ai(0, KAKAO), ai(9, DIALER), ai(2, NAVER_MAP)),
                Collections.<String>emptyList(),
                installed(KAKAO, DIALER, NAVER_MAP));

        assertEquals(1, plan.size());
        assertEquals(2, plan.get(0).slot);
        assertEquals(NAVER_MAP, plan.get(0).packageName);
    }

    @Test
    public void AI가_같은_단추를_두_번_채우려_하면_먼저_것만_남긴다() {
        List<SlotPlanner.Assignment> plan = SlotPlanner.reconcile(
                Arrays.asList(ai(1, KAKAO), ai(1, DIALER)),
                Collections.<String>emptyList(),
                installed(KAKAO, DIALER));

        assertEquals(1, plan.size());
        assertEquals(KAKAO, plan.get(0).packageName);
    }

    @Test
    public void AI가_아예_답을_못_줘도_사전만으로_배치가_된다() {
        List<SlotPlanner.Assignment> plan = SlotPlanner.reconcile(
                null,
                Arrays.asList("call", "kakao", "video"),
                installed(DIALER, KAKAO, YOUTUBE));

        assertEquals(3, plan.size());
        assertEquals(0, SlotPlanner.countFromAi(plan));
        assertEquals(DIALER, plan.get(0).packageName);
        assertEquals(KAKAO, plan.get(1).packageName);
        assertEquals(YOUTUBE, plan.get(2).packageName);
    }

    @Test
    public void 사전이_채울_때_AI가_이미_쓴_앱은_다시_쓰지_않는다() {
        // AI가 2번에 전화를 넣었으니, "전화 걸기"를 고르셨어도 1번에 또 넣으면 안 됩니다.
        List<SlotPlanner.Assignment> plan = SlotPlanner.reconcile(
                Collections.singletonList(ai(2, DIALER)),
                Arrays.asList("call", "map"),
                installed(DIALER, NAVER_MAP));

        assertEquals(2, plan.size());
        assertEquals(NAVER_MAP, plan.get(0).packageName);
        assertEquals(DIALER, plan.get(1).packageName);
    }

    @Test
    public void 결과는_항상_단추_번호_순서로_정렬된다() {
        List<SlotPlanner.Assignment> plan = SlotPlanner.reconcile(
                Arrays.asList(ai(3, KAKAO), ai(1, DIALER), ai(2, NAVER_MAP)),
                Collections.<String>emptyList(),
                installed(KAKAO, DIALER, NAVER_MAP));

        assertEquals(1, plan.get(0).slot);
        assertEquals(2, plan.get(1).slot);
        assertEquals(3, plan.get(2).slot);
    }

    @Test
    public void 깔린_앱_목록이_비어_있으면_아무것도_넣지_않는다() {
        List<SlotPlanner.Assignment> plan = SlotPlanner.reconcile(
                Arrays.asList(ai(1, KAKAO)),
                Arrays.asList("call", "map"),
                new ArrayList<String>());

        assertTrue(plan.isEmpty());
    }

    private static boolean installedContains(String pkg) {
        return installed(DIALER, NAVER_MAP).contains(pkg);
    }
}
