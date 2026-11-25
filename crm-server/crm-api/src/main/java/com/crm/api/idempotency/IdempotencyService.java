package com.crm.api.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Explicit, synchronous idempotency boundary. See README in this package before integration. */
@Component
public class IdempotencyService {
    private static final Duration TTL = Duration.ofHours(24);
    private static final DefaultRedisScript<Long> COMPLETE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "redis.call('set', KEYS[1], ARGV[2], 'EX', ARGV[3]); return 1 end; return 0", Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public IdempotencyService(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    /**
     * Invoke only after authentication and authorization. The caller supplies the exact request body,
     * method, path and query (including all business-significant inputs), and serializes the complete
     * HTTP result inside operation. Never use for streaming, asynchronous or multipart requests.
     */
    public Response execute(long userId, String key, String method, String target, byte[] body,
                            Supplier<Response> operation) {
        if (userId <= 0 || key == null || key.isBlank() || key.length() > 200
                || key.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new Rejected(Reason.INVALID_KEY);
        }
        Objects.requireNonNull(method);
        Objects.requireNonNull(target);
        Objects.requireNonNull(body);
        Objects.requireNonNull(operation);
        String redisKey = "idem:v1:" + digest(Long.toString(userId), key);
        String fingerprint = digest(method, target, Base64.getEncoder().encodeToString(body));
        String claim = "P:" + fingerprint + ":" + UUID.randomUUID();
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(redisKey, claim);
            if (acquired == null) throw new Rejected(Reason.STORE_UNAVAILABLE);
            if (!acquired) {
                String existing = redis.opsForValue().get(redisKey);
                if (existing == null) throw new Rejected(Reason.STORE_UNAVAILABLE);
                if (existing.startsWith("P:")) {
                    if (!existing.startsWith("P:" + fingerprint + ":")) throw new Rejected(Reason.CONFLICT);
                    throw new Rejected(Reason.IN_PROGRESS);
                }
                Stored stored = mapper.readValue(existing, Stored.class);
                if (!fingerprint.equals(stored.fingerprint())) throw new Rejected(Reason.CONFLICT);
                return stored.response();
            }
        } catch (Rejected e) {
            throw e;
        } catch (Exception e) {
            throw new Rejected(Reason.STORE_UNAVAILABLE, e);
        }

        // Do not release on exceptions: the operation may already have committed.
        Response result = Objects.requireNonNull(operation.get());
        try {
            String completed = mapper.writeValueAsString(new Stored(fingerprint, result));
            Long updated = redis.execute(COMPLETE, List.of(redisKey), claim, completed,
                    Long.toString(TTL.toSeconds()));
            if (!Long.valueOf(1).equals(updated)) throw new Rejected(Reason.STORE_UNAVAILABLE);
            return result;
        } catch (Rejected e) {
            throw e;
        } catch (Exception e) {
            throw new Rejected(Reason.STORE_UNAVAILABLE, e);
        }
    }

    private static String digest(String... values) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                md.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                md.update(bytes);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Response(int status, String contentType, byte[] body) {
        public Response {
            if (status < 100 || status > 599) throw new IllegalArgumentException("Invalid HTTP status");
            Objects.requireNonNull(contentType);
            body = Objects.requireNonNull(body).clone();
        }
        @Override public byte[] body() { return body.clone(); }
    }
    public record Stored(String fingerprint, Response response) { }
    public enum Reason { INVALID_KEY, CONFLICT, IN_PROGRESS, STORE_UNAVAILABLE }
    public static final class Rejected extends RuntimeException {
        private final Reason reason;
        public Rejected(Reason reason) { super(reason.name()); this.reason = reason; }
        public Rejected(Reason reason, Throwable cause) { super(reason.name(), cause); this.reason = reason; }
        public Reason reason() { return reason; }
    }
}
