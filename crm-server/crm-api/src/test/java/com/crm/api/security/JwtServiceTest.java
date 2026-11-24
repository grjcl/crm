package com.crm.api.security;

import java.time.Duration;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private final JwtService jwtService = new JwtService(SECRET, Duration.ofHours(2), Duration.ofDays(30));

    @Test
    void issuesAccessAndRefreshWithDistinctJti() {
        JwtService.TokenPair pair = jwtService.issueTokens(7L, "SALES");

        assertNotEquals(pair.access().jti(), pair.refresh().jti());
        assertEquals(JwtService.TYPE_ACCESS, pair.access().type());
        assertEquals(JwtService.TYPE_REFRESH, pair.refresh().type());
        assertEquals(Duration.ofHours(2).toSeconds(),
                Duration.between(pair.access().issuedAt(), pair.access().expiresAt()).toSeconds());
    }

    @Test
    void parsesAccessToken() {
        JwtService.TokenPair pair = jwtService.issueTokens(7L, "SALES");

        JwtService.ParsedToken parsed = jwtService.parse(pair.access().value(), JwtService.TYPE_ACCESS);

        assertEquals(7L, parsed.userId());
        assertEquals("SALES", parsed.role());
        assertEquals(pair.access().jti(), parsed.jti());
        assertEquals(JwtService.TYPE_ACCESS, parsed.type());
    }

    @Test
    void rejectsRefreshTokenUsedAsAccessToken() {
        JwtService.TokenPair pair = jwtService.issueTokens(7L, "SALES");

        assertThrows(JwtException.class,
                () -> jwtService.parse(pair.refresh().value(), JwtService.TYPE_ACCESS));
    }

    @Test
    void rejectsTokenWithTamperedSignature() {
        JwtService.TokenPair pair = jwtService.issueTokens(7L, "SALES");
        String token = pair.access().value();
        char last = token.charAt(token.length() - 1);
        String tampered = token.substring(0, token.length() - 1) + (last == 'a' ? 'b' : 'a');

        assertThrows(JwtException.class,
                () -> jwtService.parse(tampered, JwtService.TYPE_ACCESS));
    }
}
