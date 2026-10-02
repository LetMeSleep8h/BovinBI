package com.eighthours.bovinbi.security;

import com.eighthours.bovinbi.common.BizException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 登录防爆破(进程内,Caffeine):同一用户名在窗口期内连续失败 N 次 → 临时锁定 T 秒。
 * - 按用户名维度限流(而非 IP):演示部署单机有效;生产多实例应换 Redis 计数,接口不变;
 * - 锁定期间正确密码也拒绝(否则攻击者可以继续撞库,只错密码会被拦);
 * - 锁定过期自动放行,成功登录清零计数 —— 惩罚爆破者,不影响正常用户。
 * 锁定用户名包括不存在的用户:避免"用户名枚举 + 只对存在账号爆破"的绕过。
 */
@Component
public class LoginGuard {

    /** 状态:窗口内连续失败数 + 锁定截止时间戳(0=未锁) */
    private record Fails(int count, long lockedUntil) {
    }

    private final Cache<String, Fails> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(30)) // 失败计数的统计窗口
            .build();

    /** 登录前检查:仍在锁定期 → 拒绝(429) */
    public void ensureNotLocked(String username) {
        Fails f = cache.getIfPresent(username);
        if (f != null && f.lockedUntil() > System.currentTimeMillis()) {
            long wait = (f.lockedUntil() - System.currentTimeMillis()) / 1000 + 1;
            throw new BizException(429, "登录失败次数过多,已临时锁定,请 " + wait + " 秒后再试");
        }
    }

    /** 登录失败登记:达到阈值则上锁;返回是否触发锁定(供日志/审计) */
    public boolean recordFailure(String username, int maxFailures, long lockMillis) {
        Fails prev = cache.getIfPresent(username);
        int count = (prev == null || prev.lockedUntil() > 0 ? 0 : prev.count()) + 1;
        boolean lock = count >= maxFailures;
        cache.put(username, new Fails(lock ? 0 : count, lock ? System.currentTimeMillis() + lockMillis : 0));
        return lock;
    }

    /** 登录成功清零 */
    public void reset(String username) {
        cache.invalidate(username);
    }
}
