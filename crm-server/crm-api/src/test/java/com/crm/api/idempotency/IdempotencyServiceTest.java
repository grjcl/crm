package com.crm.api.idempotency;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

class IdempotencyServiceTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final IdempotencyService service = new IdempotencyService(redis, mapper);
    private final byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

    @BeforeEach void setup() { when(redis.opsForValue()).thenReturn(values); }

    @Test void firstExecutionPublishesFor24Hours() {
        when(values.setIfAbsent(anyString(), anyString())).thenReturn(true);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(1L);
        var result = service.execute(1, "key", "POST", "/path", body,
                () -> new IdempotencyService.Response(201, "application/json", body));
        assertEquals(201, result.status());
        verify(redis).execute(any(RedisScript.class), anyList(), any(), any(), eq("86400"));
    }

    @Test void replayDoesNotRunBusiness() throws Exception {
        when(values.setIfAbsent(anyString(), anyString())).thenReturn(false);
        // Capture a real fingerprint by observing an initial claim.
        when(values.setIfAbsent(anyString(), anyString())).thenAnswer(inv -> {
            String claim = inv.getArgument(1);
            String fingerprint = claim.split(":")[1];
            when(values.get(anyString())).thenReturn(mapper.writeValueAsString(
                    new IdempotencyService.Stored(fingerprint, new IdempotencyService.Response(202, "text/plain", body))));
            return false;
        });
        AtomicInteger calls = new AtomicInteger();
        var response = service.execute(1, "key", "POST", "/path", body, () -> { calls.incrementAndGet(); return null; });
        assertEquals(0, calls.get());
        assertEquals(202, response.status());
        assertArrayEquals(body, response.body());
    }

    @Test void failuresAreConservative() {
        assertEquals(IdempotencyService.Reason.INVALID_KEY, assertThrows(IdempotencyService.Rejected.class,
                () -> service.execute(1, " ", "POST", "/", body, () -> null)).reason());
        when(values.setIfAbsent(anyString(), anyString())).thenThrow(new IllegalStateException());
        assertEquals(IdempotencyService.Reason.STORE_UNAVAILABLE, assertThrows(IdempotencyService.Rejected.class,
                () -> service.execute(1, "key", "POST", "/", body, () -> fail())).reason());
        doReturn(true).when(values).setIfAbsent(anyString(), anyString());
        assertThrows(IllegalStateException.class,
                () -> service.execute(1, "key", "POST", "/", body, () -> { throw new IllegalStateException(); }));
        verify(values, never()).get(anyString());
        verify(redis, never()).delete(anyString());
    }
}
