package com.eighthours.bovinbi.service;

import com.eighthours.bovinbi.support.MySqlTestBase;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eighthours.bovinbi.common.BizException;
import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.controller.HistoryController;
import com.eighthours.bovinbi.dto.LoginResp;
import com.eighthours.bovinbi.entity.Dataset;
import com.eighthours.bovinbi.mapper.DatasetMapper;
import com.eighthours.bovinbi.mapper.UserMapper;
import com.eighthours.bovinbi.security.UserContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户数据隔离端到端契约(真实注册两用户 + 真实问答链路,模拟 AuthInterceptor 的身份注入):
 * 1) 会话域:列表/消息/删除全部按属主隔离,B 访问 A 的会话一律 404(不暴露存在性);
 * 2) 审计域:普通用户只见自己的 query_log,ADMIN 全量(scope=ALL);
 * 3) 配置域:数据集口径修改仅 ADMIN,普通用户 403;
 * 4) 数据集目录是全局共享读 —— 共享的是"表结构",隔离的是"行为数据"。
 */
@SpringBootTest
class UserIsolationTest extends MySqlTestBase {

    @Autowired
    private AuthService authService;
    @Autowired
    private ChatService chatService;
    @Autowired
    private HistoryController historyController;
    @Autowired
    private DatasetService datasetService;
    @Autowired
    private DatasetMapper datasetMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private BovinProperties props;

    private final String uniq = Long.toString(System.nanoTime(), 36);

    private Long uidOf(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<com.eighthours.bovinbi.entity.User>()
                .eq(com.eighthours.bovinbi.entity.User::getUsername, username)).getId();
    }

    /** 模拟 AuthInterceptor:请求内注入身份,请求结束清理(有返回值动作) */
    private <T> T runAs(Long uid, String role, Supplier<T> action) {
        UserContext.set(uid, "u" + uid, role);
        try {
            return action.get();
        } finally {
            UserContext.clear();
        }
    }

    /** void 动作版(越权尝试/无返回步骤),避免 void lambda 与 Supplier 的推断冲突 */
    private void runAsVoid(Long uid, String role, Runnable action) {
        UserContext.set(uid, "u" + uid, role);
        try {
            action.run();
        } finally {
            UserContext.clear();
        }
    }

    private Long dairyDatasetId() {
        return datasetMapper.selectOne(new LambdaQueryWrapper<Dataset>()
                .eq(Dataset::getName, "牧场养殖分析").last("LIMIT 1")).getId();
    }

    @Test
    void sessionsAreIsolatedPerUser() {
        LoginResp alice = authService.register("iso_a_" + uniq, "abcd1234", null);
        LoginResp bob = authService.register("iso_b_" + uniq, "abcd1234", null);
        Long dsId = dairyDatasetId();

        Long aliceSession = runAs(alice.id(), "ANALYST", () -> chatService.createSession(dsId));
        runAs(alice.id(), "ANALYST", () -> chatService.ask(aliceSession, "今年总产奶量"));

        // Alice:自己的会话与消息可见
        assertTrue(runAs(alice.id(), "ANALYST", () ->
                chatService.listSessions().stream().anyMatch(s -> s.id().equals(aliceSession))));
        assertEquals(2, runAs(alice.id(), "ANALYST", () -> chatService.messages(aliceSession)).size());

        // Bob:看不到 Alice 的会话,直接访问一律 404(存在性都不给)
        assertTrue(runAs(bob.id(), "ANALYST", () -> chatService.listSessions().stream()
                .noneMatch(s -> s.id().equals(aliceSession))));
        assertEquals(404, assertThrows(BizException.class, () ->
                runAsVoid(bob.id(), "ANALYST", () -> chatService.messages(aliceSession))).getCode());
        assertEquals(404, assertThrows(BizException.class, () ->
                runAsVoid(bob.id(), "ANALYST", () -> chatService.deleteSession(aliceSession))).getCode());
        assertNotEquals(0, runAs(alice.id(), "ANALYST", () -> chatService.listSessions()).size(),
                "Bob 的越权访问不得影响 Alice 数据");
    }

    @Test
    void auditLogsScopedToOwnerUnlessAdmin() {
        LoginResp alice = authService.register("aud_a_" + uniq, "abcd1234", null);
        LoginResp bob = authService.register("aud_b_" + uniq, "abcd1234", null);
        Long dsId = dairyDatasetId();
        Long session = runAs(alice.id(), "ANALYST", () -> chatService.createSession(dsId));
        runAs(alice.id(), "ANALYST", () -> chatService.ask(session, "今年总产奶量"));

        // Alice 只见自己的;Bob 一条都看不到
        runAs(alice.id(), "ANALYST", () -> {
            var page = historyController.page(1, 50);
            assertTrue(page.getData().getRecords().stream().allMatch(l -> alice.id().equals(l.getUserId())));
            assertTrue(page.getData().getRecords().stream().anyMatch(l -> alice.id().equals(l.getUserId())));
            assertEquals("SELF", historyController.stats().getData().get("scope"));
            return null;
        });
        runAs(bob.id(), "ANALYST", () -> {
            assertTrue(historyController.page(1, 50).getData().getRecords().stream()
                    .noneMatch(l -> alice.id().equals(l.getUserId())));
            return null;
        });

        // ADMIN 全量可见(管理审计),scope=ALL
        Long adminId = uidOf("admin");
        runAs(adminId, "ADMIN", () -> {
            assertTrue(historyController.page(1, 50).getData().getRecords().stream()
                    .anyMatch(l -> alice.id().equals(l.getUserId())));
            assertEquals("ALL", historyController.stats().getData().get("scope"));
            return null;
        });
    }

    @Test
    void datasetConfigChangeIsAdminOnly() {
        LoginResp plain = authService.register("cfg_u_" + uniq, "abcd1234", null);
        var firstField = runAs(plain.id(), "ANALYST", () ->
                datasetService.fields(dairyDatasetId()).get(0));

        // 普通用户:读目录可以,改口径 403
        runAsVoid(plain.id(), "ANALYST", () -> datasetService.list());
        assertEquals(403, assertThrows(BizException.class, () -> runAsVoid(plain.id(), "ANALYST", () ->
                datasetService.updateField(firstField.getId(), new com.eighthours.bovinbi.dto.FieldUpdateReq(
                        firstField.getAlias(), null, null, null)))).getCode());

        // ADMIN:允许修改,并恢复原值
        Long adminId = uidOf("admin");
        runAsVoid(adminId, "ADMIN", () -> datasetService.updateField(firstField.getId(),
                new com.eighthours.bovinbi.dto.FieldUpdateReq(firstField.getAlias(), null, null, null)));
    }
}
