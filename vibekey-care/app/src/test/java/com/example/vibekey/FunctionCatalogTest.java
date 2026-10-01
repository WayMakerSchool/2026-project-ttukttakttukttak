package com.example.vibekey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 첫 실행에서 어르신께 보여 드리는 "자주 하는 일" 목록을 검증합니다.
 * 아이디가 겹치거나 후보 앱이 비어 있으면 그 칸은 눌러도 아무 앱도 안 잡히므로 미리 막습니다.
 */
public class FunctionCatalogTest {

    @Test
    public void 목록이_비어_있지_않다() {
        assertTrue(FunctionCatalog.size() >= 10);
        assertEquals(FunctionCatalog.size(), FunctionCatalog.all().size());
    }

    @Test
    public void 아이디가_겹치지_않는다() {
        Set<String> ids = new HashSet<>();
        for (FunctionCatalog.Function f : FunctionCatalog.all()) {
            assertTrue("아이디가 겹칩니다: " + f.id, ids.add(f.id));
        }
    }

    @Test
    public void 모든_칸에_이름과_그림과_후보_앱이_있다() {
        for (FunctionCatalog.Function f : FunctionCatalog.all()) {
            assertFalse("이름이 비었습니다: " + f.id, f.label.trim().isEmpty());
            assertFalse("그림 이름이 비었습니다: " + f.id, f.iconName.trim().isEmpty());
            assertNotNull("후보 앱이 없습니다: " + f.id, f.candidates);
            assertTrue("후보 앱이 없습니다: " + f.id, f.candidates.length > 0);
            for (String candidate : f.candidates) {
                assertFalse("빈 패키지명: " + f.id, candidate.trim().isEmpty());
            }
        }
    }

    @Test
    public void 아이디로_찾을_수_있다() {
        FunctionCatalog.Function call = FunctionCatalog.byId("call");
        assertNotNull(call);
        assertEquals("전화 걸기", call.label);
        assertNull(FunctionCatalog.byId("없는아이디"));
        assertNull(FunctionCatalog.byId(null));
    }

    @Test
    public void 모르는_아이디는_그대로_돌려_준다() {
        assertEquals("전화 걸기", FunctionCatalog.labelOf("call"));
        assertEquals("없는아이디", FunctionCatalog.labelOf("없는아이디"));
    }

    @Test
    public void AI에게_보낼_문장을_만든다() {
        assertEquals("전화 걸기, 길 찾기",
                FunctionCatalog.labelsOf(Arrays.asList("call", "map")));
        assertEquals("", FunctionCatalog.labelsOf(new ArrayList<String>()));
        assertEquals("", FunctionCatalog.labelsOf(null));
    }

    @Test
    public void 목록은_밖에서_고칠_수_없다() {
        List<FunctionCatalog.Function> all = FunctionCatalog.all();
        try {
            all.clear();
            throw new AssertionError("목록이 수정되었습니다. 읽기 전용이어야 합니다.");
        } catch (UnsupportedOperationException expected) {
            assertEquals(FunctionCatalog.size(), FunctionCatalog.all().size());
        }
    }

    @Test
    public void 가장_급한_전화_걸기가_맨_앞에_있다() {
        // 어르신이 가장 자주, 가장 급하게 쓰시는 일이라 첫 칸에 두었습니다.
        assertEquals("call", FunctionCatalog.all().get(0).id);
    }
}
