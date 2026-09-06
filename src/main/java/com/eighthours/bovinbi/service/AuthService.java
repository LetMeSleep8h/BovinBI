package com.eighthours.bovinbi.service;

import com.eighthours.bovinbi.common.BizException;
import com.eighthours.bovinbi.dto.LoginResp;
import com.eighthours.bovinbi.entity.User;
import com.eighthours.bovinbi.mapper.UserMapper;
import com.eighthours.bovinbi.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public LoginResp login(String username, String password) {
        User u = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                        .eq(User::getUsername, username));
        if (u == null || !encoder.matches(password, u.getPassword())) {
            throw new BizException(401, "用户名或密码错误");
        }
        return new LoginResp(jwtUtil.issue(u.getId(), u.getUsername()), u.getId(),
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
