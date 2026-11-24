package com.crm.api.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * JWT 签发与校验（HS256，access 2h / refresh 30d，时钟偏移容忍 60s）。
 */
@Service
public class JwtService {

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public JwtService(@Value("${crm.jwt.secret}") String secret,
                      @Value("${crm.jwt.access-ttl:2h}") Duration accessTtl,
                      @Value("${crm.jwt.refresh-ttl:30d}") Duration refreshTtl) {
        byte[] keyBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("crm.jwt.secret 至少需要 32 字节（HS256）");
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
    }

    public TokenPair issueTokens(Long userId, String role) {
        Instant now = Instant.now();
        Token access = build(userId, role, TYPE_ACCESS, now, now.plus(accessTtl));
        Token refresh = build(userId, role, TYPE_REFRESH, now, now.plus(refreshTtl));
        return new TokenPair(access, refresh);
    }

    /**
     * 解析并校验签名、过期时间与 token 类型。
     *
     * @throws JwtException 签名不符、已过期或类型不匹配
     */
    public ParsedToken parse(String token, String expectedType) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .clockSkewSeconds(60)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        String type = claims.get("type", String.class);
        if (!expectedType.equals(type)) {
            throw new JwtException("token 类型不匹配");
        }
        Long userId = Long.valueOf(claims.getSubject());
        String role = claims.get("role", String.class);
        return new ParsedToken(
                userId,
                role,
                claims.getId(),
                claims.getIssuedAt().toInstant(),
                claims.getExpiration().toInstant(),
                type);
    }

    public long accessTtlSeconds() {
        return accessTtl.toSeconds();
    }

    private Token build(Long userId, String role, String type, Instant issuedAt, Instant expiresAt) {
        String jti = UUID.randomUUID().toString();
        String value = Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role)
                .claim("type", type)
                .id(jti)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new Token(value, jti, issuedAt, expiresAt, type);
    }

    public record Token(String value, String jti, Instant issuedAt, Instant expiresAt, String type) {
    }

    public record TokenPair(Token access, Token refresh) {
    }

    public record ParsedToken(Long userId, String role, String jti,
                              Instant issuedAt, Instant expiresAt, String type) {
    }
}
