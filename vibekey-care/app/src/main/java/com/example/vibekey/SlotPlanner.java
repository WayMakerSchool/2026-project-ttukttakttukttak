package com.example.vibekey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 고르신 "자주 하는 일"을 기기 단추 1·2·3번에 배치하는 계획을 세웁니다.
 *
 * 두 가지 일을 합니다.
 *  1. {@link #planOffline} — 인터넷이나 AI를 못 쓸 때 키워드 사전만으로 배치합니다.
 *  2. {@link #reconcile}   — AI가 내놓은 배치를 <b>검증</b>합니다.
 *     AI가 이 휴대폰에 없는 앱을 고르거나, 같은 앱을 두 단추에 넣거나,
 *     단추 번호를 엉뚱하게 적어도 여기서 걸러 내고 빈자리는 오프라인 계획으로 채웁니다.
 *
 * AI 답을 그대로 믿지 않고 항상 이 검증을 거치기 때문에,
 * AI가 이상한 답을 줘도 어르신 화면에는 실제로 열리는 앱만 올라갑니다.
 *
 * 안드로이드 클래스를 쓰지 않는 순수 자바라서 단위 테스트로 바로 검증할 수 있습니다.
 * (app/src/test/java/com/example/vibekey/SlotPlannerTest.java)
 */
public final class SlotPlanner {

    /** 단추 하나에 대한 배치 결과 */
    public static final class Assignment {
        public int slot;
        public String packageName = "";
        public String label = "";
        public String reason = "";
        /** true 면 AI가 고른 것, false 면 사전으로 대신 채운 것 */
        public boolean fromAi;

        public Assignment() {
        }

        public Assignment(int slot, String packageName, String label, String reason, boolean fromAi) {
            this.slot = slot;
            this.packageName = packageName == null ? "" : packageName;
            this.label = label == null ? "" : label;
            this.reason = reason == null ? "" : reason;
            this.fromAi = fromAi;
        }
    }

    private SlotPlanner() {
    }

    /**
     * AI 없이, 고르신 순서대로 설치된 앱을 찾아 단추에 채웁니다.
     *
     * @param functionIds       고르신 기능 아이디들 (고른 순서대로)
     * @param installedPackages 이 휴대폰에 실제로 깔린 패키지명 모음
     */
    public static List<Assignment> planOffline(List<String> functionIds,
                                               Collection<String> installedPackages) {
        List<Assignment> result = new ArrayList<>();
        if (functionIds == null || functionIds.isEmpty()) {
            return result;
        }
        Set<String> installed = safeSet(installedPackages);
        Set<String> used = new HashSet<>();
        int slot = 1;

        for (String id : functionIds) {
            if (slot > Prefs.SLOT_COUNT) {
                break;
            }
            FunctionCatalog.Function function = FunctionCatalog.byId(id);
            if (function == null) {
                continue;
            }
            String picked = firstInstalled(function.candidates, installed, used);
            if (picked == null) {
                continue; // 이 일에 쓸 앱이 휴대폰에 하나도 없습니다.
            }
            used.add(picked);
            result.add(new Assignment(slot, picked, function.label,
                    "'" + function.label + "'" + KoreanParticle.eulReul(function.label) + " 고르셔서 넣었어요.", false));
            slot++;
        }
        return result;
    }

    /**
     * AI가 내놓은 배치를 검증하고, 부족한 자리는 오프라인 계획으로 채워 1~3번을 완성합니다.
     *
     * @param proposal          AI가 준 배치 (null 이거나 비어 있어도 됩니다)
     * @param functionIds       어르신이 고르신 기능 아이디들
     * @param installedPackages 이 휴대폰에 실제로 깔린 패키지명 모음
     * @return 단추 번호 순으로 정렬된 배치. 채울 앱이 없으면 그 자리는 빠집니다.
     */
    public static List<Assignment> reconcile(List<Assignment> proposal,
                                             List<String> functionIds,
                                             Collection<String> installedPackages) {
        Set<String> installed = safeSet(installedPackages);
        Set<String> usedPackages = new HashSet<>();
        Set<Integer> usedSlots = new HashSet<>();
        List<Assignment> accepted = new ArrayList<>();

        if (proposal != null) {
            for (Assignment a : proposal) {
                if (a == null) {
                    continue;
                }
                if (a.slot < 1 || a.slot > Prefs.SLOT_COUNT) {
                    continue; // 없는 단추 번호
                }
                if (usedSlots.contains(a.slot)) {
                    continue; // 같은 단추를 두 번 채우려 함
                }
                String pkg = a.packageName == null ? "" : a.packageName.trim();
                if (pkg.isEmpty() || !installed.contains(pkg)) {
                    continue; // AI가 없는 앱을 지어냈습니다.
                }
                if (usedPackages.contains(pkg)) {
                    continue; // 같은 앱을 두 단추에 넣으려 함
                }
                usedSlots.add(a.slot);
                usedPackages.add(pkg);
                accepted.add(new Assignment(a.slot, pkg, a.label, a.reason, true));
            }
        }

        // 남은 단추를 사전 계획으로 채웁니다.
        for (Assignment fallback : planOffline(functionIds, installed)) {
            if (usedPackages.contains(fallback.packageName)) {
                continue;
            }
            int free = firstFreeSlot(usedSlots);
            if (free == 0) {
                break;
            }
            usedSlots.add(free);
            usedPackages.add(fallback.packageName);
            accepted.add(new Assignment(free, fallback.packageName, fallback.label,
                    fallback.reason, false));
        }

        Collections.sort(accepted, new Comparator<Assignment>() {
            @Override
            public int compare(Assignment a, Assignment b) {
                return Integer.compare(a.slot, b.slot);
            }
        });
        return accepted;
    }

    /** AI가 고른 것이 몇 개인지 (화면에 "AI가 정했어요"를 보여 줄지 판단할 때 씁니다) */
    public static int countFromAi(List<Assignment> assignments) {
        int count = 0;
        if (assignments != null) {
            for (Assignment a : assignments) {
                if (a != null && a.fromAi) {
                    count++;
                }
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ 내부

    private static String firstInstalled(String[] candidates, Set<String> installed, Set<String> used) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (installed.contains(candidate) && !used.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static int firstFreeSlot(Set<Integer> usedSlots) {
        for (int slot = 1; slot <= Prefs.SLOT_COUNT; slot++) {
            if (!usedSlots.contains(slot)) {
                return slot;
            }
        }
        return 0;
    }

    private static Set<String> safeSet(Collection<String> packages) {
        return packages == null ? Collections.<String>emptySet() : new HashSet<>(packages);
    }
}
