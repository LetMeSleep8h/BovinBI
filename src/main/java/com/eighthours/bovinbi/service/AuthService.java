package com.eighthours.bovinbi.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eighthours.bovinbi.common.BizException;
import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.dto.LoginResp;
import com.eighthours.bovinbi.entity.User;
import com.eighthours.bovinbi.mapper.UserMapper;
import com.eighthours.bovinbi.security.JwtUtil;
import com.eighthours.bovinbi.security.LoginGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * 统一认证服务(用户登录的全部入口,收敛到一处 —— 项目里不再有第二套账号体系):
 * - 登录:防爆破锁定(LoginGuard)+ 时序均化(用户不存在也做一次同代价 BCrypt 比较,
 *   消除"响应快慢枚举用户名"的侧信道)+ 统一 401 文案(不区分"用户不存在/密码错");
 * - 注册:用户名/密码策略校验 + 唯一性,成功即发 token(注册即登录);
 * - token:JWT 携带 uid/username/role,无状态、不落库(服务端存 token 既无必要也有泄露面)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** 用户名:3~32 位字母数字下划线(登录名非昵称,约束字符集降低注入面) */
    private static final Pattern USERNAME = Pattern.compile("^[a-zA-Z0-9_]{3,32}$");
    /** 密码:>=8 位且同时含字母与数字(BCrypt 之外的最低策略;生产可再接密码字典) */
    private static final Pattern PASSWORD_LETTER = Pattern.compile("[a-zA-Z]");
    private static final Pattern PASSWORD_DIGIT = Pattern.compile("\\d");

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final LoginGuard loginGuard;
    private final BovinProperties props;
    /** 时序均化用的哑哈希:用户不存在时也执行一次同代价 matches,响应时间与密码错误不可区分 */
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final String dummyHash = encoder.encode("bovin-timing-equalizer");

    public LoginResp login(String username, String password) {
        loginGuard.ensureNotLocked(username); // 锁定期内连正确密码也拒(429)
        User u = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username));
        boolean ok;
        if (u != null) {
            ok = encoder.matches(password, u.getPassword());
        } else {
            encoder.matches(password, dummyHash); // 同代价比较,消侧信道
            ok = false;
        }
        if (!ok) {
            boolean locked = loginGuard.recordFailure(username,
                    props.getSecurity().getMaxLoginFailures(),
                    props.getSecurity().getLoginLockSeconds() * 1000L);
            if (locked) {
                log.warn("账号 [{}] 连续登录失败 {} 次,已临时锁定 {} 秒", username,
                        props.getSecurity().getMaxLoginFailures(), props.getSecurity().getLoginLockSeconds());
            }
            throw new BizException(401, "用户名或密码错误");
        }
        loginGuard.reset(username);
        return new LoginResp(jwtUtil.issue(u.getId(), u.getUsername(), u.getRole()), u.getId(),
                u.getUsername(), u.getNickname(), u.getRole());
    }

    /** 自助注册(bovin.security.register-enabled 可关):成功即返回 token,注册即登录 */
    public LoginResp register(String username, String password, String nickname) {
        if (!props.getSecurity().isRegisterEnabled()) {
            throw new BizException(403, "注册未开放,请联系管理员开通账号");
        }
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new BizException(400, "用户名需 3~32 位字母/数字/下划线");
        }
        if (password == null || password.length() < 8
                || !PASSWORD_LETTER.matcher(password).find() || !PASSWORD_DIGIT.matcher(password).find()) {
            throw new BizException(400, "密码至少 8 位,且需同时包含字母和数字");
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, username)) > 0) {
            throw new BizException(409, "用户名已存在");
        }
        User u = new User();
        u.setUsername(username);
        u.setPassword(encoder.encode(password));
        u.setNickname(nickname == null || nickname.isBlank() ? username : nickname);
        u.setRole("ANALYST"); // 自助注册一律普通角色;ADMIN 只能由种子数据/管理员授予
        userMapper.insert(u);
        log.info("新用户注册: {}", username);
        return new LoginResp(jwtUtil.issue(u.getId(), u.getUsername(), u.getRole()), u.getId(),
                u.getUsername(), u.getNickname(), u.getRole());
    }

    public User me(Long uid) {
        User u = userMapper.selectById(uid);
        if (u == null) {
            throw new BizException(401, "用户不存在");
        }
        u.setPassword(null);
        return u;
    }

    /** 供 DataLoader 初始化种子用户 */
    public String encode(String raw) {
        return encoder.encode(raw);
    }
}
