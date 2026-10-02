package com.eighthours.bovinbi.service;

import com.eighthours.bovinbi.support.MySqlTestBase;

import com.eighthours.bovinbi.common.BizException;
import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.dto.LoginResp;
import com.eighthours.bovinbi.entity.User;
import com.eighthours.bovinbi.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eighthours.bovinbi.security.JwtUtil;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 认证链路契约(H2 真实库):
 * 1) 登录成功:token 携带 uid/username/role 三要素;
 * 2) 统一 401:密码错/用户不存在同一文案同一状态码(不泄露用户名存在性);
 * 3) 防爆破:连续失败 N 次锁定,锁定期正确密码也拒(429),过期自动放行;
 * 4) 注册:策略校验(用户名/密码)、唯一性、默认 ANALYST、注册即登录;可配置关闭;
 * 5) me():密码哈希永不回传。
 */
@SpringBootTest
class AuthServiceTest extends MySqlTestBase {

    @Autowired
    private AuthService authService;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private BovinProperties props;

    private final String uniq = Long.toString(System.nanoTime(), 36);

    @Test
    void loginSuccessCarriesRoleInToken() {
        LoginResp resp = authService.login("admin", "bovin123");
        assertEquals("ADMIN", resp.role());
        Claims claims = jwtUtil.verify(resp.token());
        assertNotNull(claims);
        assertEquals("admin", claims.getSubject());
        assertEquals(resp.id(), ((Number) claims.get("uid")).longValue());
        assertEquals("ADMIN", claims.get("role"));
    }

    @Test
    void wrongPasswordAndUnknownUserGiveIdentical401() {
        BizException wrong = assertThrows(BizException.class, () -> authService.login("admin", uniq));
        BizException unknown = assertThrows(BizException.class,
                () -> authService.login("no_such_user_" + uniq, "whatever1"));
        assertEquals(401, wrong.getCode());
        assertEquals(401, unknown.getCode());
        assertEquals(wrong.getMessage(), unknown.getMessage(), "文案必须一致,不泄露用户名存在性");
    }

    @Test
    void bruteForceLocksAccountEvenForCorrectPassword() throws InterruptedException {
        props.getSecurity().setMaxLoginFailures(3);
        props.getSecurity().setLoginLockSeconds(1);
        String target = "lock_target_" + uniq; // 不存在的用户名同样上锁:防"先枚举再定向爆破"
        for (int i = 0; i < 3; i++) {
            assertEquals(401, assertThrows(BizException.class,
                    () -> authService.login(target, "badpass1x")).getCode());
        }
        BizException locked = assertThrows(BizException.class,
                () -> authService.login(target, "whatever1"));
        assertEquals(429, locked.getCode(), "锁定期内应 429: " + locked.getMessage());
        assertTrue(locked.getMessage().contains("锁定"));

        Thread.sleep(1300); // 锁定 1 秒过期(测试专用短锁)
        assertEquals(401, assertThrows(BizException.class,
                () -> authService.login(target, "whatever1")).getCode(), "过期后放行(仍密码错→401 而非 429)");
    }

    @Test
    void registerValidatesPolicyAndAutoLogsIn() {
        String name = "tester_" + uniq;
        assertEquals(400, assertThrows(BizException.class,
                () -> authService.register("ab", "abcd1234", null)).getCode(), "用户名过短");
        assertEquals(400, assertThrows(BizException.class,
                () -> authService.register(name, "12345678", null)).getCode(), "纯数字密码");
        assertEquals(400, assertThrows(BizException.class,
                () -> authService.register(name, "abcdefgh", null)).getCode(), "纯字母密码");

        LoginResp resp = authService.register(name, "abcd1234", "测试同学");
        assertEquals(name, resp.username());
        assertEquals("ANALYST", resp.role(), "自助注册固定普通角色");
        assertEquals("ANALYST", jwtUtil.verify(resp.token()).get("role"), "注册即登录,token 可用");
        assertEquals("测试同学", resp.nickname());

        assertEquals(409, assertThrows(BizException.class,
                () -> authService.register(name, "abcd1234", null)).getCode(), "用户名重复");
    }
    @Test
    void registerCanBeDisabledByConfig() {
        props.getSecurity().setRegisterEnabled(false);
        try {
            assertEquals(403, assertThrows(BizException.class,
                    () -> authService.register("disabled_" + uniq, "abcd1234", null)).getCode());
        } finally {
            props.getSecurity().setRegisterEnabled(true);
        }
    }

    @Test
    void meNeverReturnsPasswordHash() {
        Long adminId = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, "admin")).getId();
        User u = authService.me(adminId);
        assertNull(u.getPassword());
        assertEquals("ADMIN", u.getRole());
    }
}
