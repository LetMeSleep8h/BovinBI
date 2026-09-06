package com.eighthours.bovinbi.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {

    private final SecretKey key;
    private final long ttlMillis;

    public JwtUtil(com.eighthours.bovinbi.config.BovinProperties props) {
        this.key = Keys.hmacShaKeyFor(props.getSecurity().getJwtSecret().getBytes(StandardCharsets.UTF_8));
        this.ttlMillis = props.getSecurity().getJwtTtlHours() * 3600_000L;
    }

    public String issue(Long userId, String username) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .claim("uid", userId)
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
