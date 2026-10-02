package com.eighthours.bovinbi.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 签发/校验:token 携带 uid + username + role 三要素,
 * role 进 token 使鉴权(ADMIN 端点)无需每次回表;角色变更需重新登录生效(可接受的无状态代价)。
 */
@Component
public class JwtUtil {

    private final SecretKey key;
    private final long ttlMillis;

    public JwtUtil(com.eighthours.bovinbi.config.BovinProperties props) {
        this.key = Keys.hmacShaKeyFor(props.getSecurity().getJwtSecret().getBytes(StandardCharsets.UTF_8));
        this.ttlMillis = props.getSecurity().getJwtTtlHours() * 3600_000L;
    }

    public String issue(Long userId, String username, String role) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .claim("uid", userId)
                .claim("role", role == null ? "ANALYST" : role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlMillis))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** 校验并返回 claims,非法返回 null */
    public Claims verify(String token) {
        try {
            return Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
        } catch (Exception e) {
            return null;
        }
    }
}
