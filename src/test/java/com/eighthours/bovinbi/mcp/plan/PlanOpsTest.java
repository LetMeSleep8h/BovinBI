package com.eighthours.bovinbi.mcp.plan;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计划校验四道关卡(学自 ai-picture-editor plan.py,Java 重写 + 写操作拦截差异化):
 * ①步数预算 ②工具存在性+写操作拦截 ③依赖补全/存在性 ④Kahn 环检测 + 拓扑序输出。
 * 拓扑排序手写实现即学习产出②。
 */
class PlanOpsTest {

    private final PlanOps ops = new PlanOps(
            Set.of("getSchema", "getColumnValues", "executeSql", "exportReport", "batchImportData"),
            Set.of("batchImportData"));

    private static Map<String, Object> step(String id, String tool, List<String> deps) {
        return Map.of("id", id, "tool", tool, "params", Map.of(), "depends_on", deps);
    }

    @Test
    void assemblesDefaultsAndTopologicalOrder() {
        // b 依赖 a;a 依赖缺省(链到前一步——首步无依赖)。乱序给出时应输出拓扑序
        List<PlanOps.Step> out = ops.assemble(List.of(
                step("b", "executeSql", List.of("a")),
                step("a", "getSchema", List.of())));
        assertEquals(List.of("a", "b"), out.stream().map(PlanOps.Step::id).toList(),
                "依赖在前(拓扑序)");
        assertEquals(List.of(), out.get(0).dependsOn());
        assertEquals(List.of("a"), out.get(1).dependsOn());
    }

    @Test
    void stepBudgetEnforced() {
        List<Map<String, Object>> tooMany = new java.util.ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            tooMany.add(step("s" + i, "getSchema", List.of()));
        }
        PlanOps.PlanException e = assertThrows(PlanOps.PlanException.class, () -> ops.assemble(tooMany));
        assertTrue(e.getMessage().contains("超过 8 步"));
    }

    @Test
    void unknownToolRejected() {
        PlanOps.PlanException e = assertThrows(PlanOps.PlanException.class,
                () -> ops.assemble(List.of(step("s1", "noSuchTool", List.of()))));
        assertTrue(e.getMessage().contains("未注册的工具"));
    }

    @Test
    void mutatorToolRejectedFromAutoPlan() {
        // 差异化关卡:参考实现没有的写操作拦截
        PlanOps.PlanException e = assertThrows(PlanOps.PlanException.class,
                () -> ops.assemble(List.of(step("s1", "batchImportData", List.of()))));
        assertTrue(e.getMessage().contains("写操作工具"));
    }

    @Test
    void missingDependencyRejected() {
        PlanOps.PlanException e = assertThrows(PlanOps.PlanException.class,
                () -> ops.assemble(List.of(step("s1", "getSchema", List.of("ghost")))));
        assertTrue(e.getMessage().contains("不存在的步骤"));
    }

    @Test
    void duplicateAndSelfDependencyRejected() {
        assertThrows(PlanOps.PlanException.class, () -> ops.assemble(List.of(
                step("s1", "getSchema", List.of()),
                Map.of("id", "s1", "tool", "getSchema", "params", Map.of(), "depends_on", List.of()))));
        assertThrows(PlanOps.PlanException.class,
                () -> ops.assemble(List.of(step("s1", "getSchema", List.of("s1")))));
    }

    @Test
    void cycleDetectedByKahn() {
        // s1 → s2 → s3 → s1:Kahn 拓扑排序访问数 < 节点数 → 成环
        PlanOps.PlanException e = assertThrows(PlanOps.PlanException.class, () -> ops.assemble(List.of(
                step("s1", "getSchema", List.of("s3")),
                step("s2", "getColumnValues", List.of("s1")),
                step("s3", "executeSql", List.of("s2")))));
        assertTrue(e.getMessage().contains("循环依赖"));
    }
}
