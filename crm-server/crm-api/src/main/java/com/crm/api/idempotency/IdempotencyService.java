package com.crm.api.idempotency;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit synchronous boundary for business writes and response in one PostgreSQL transaction. */
@Component
public class IdempotencyService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public IdempotencyService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * Call after authentication and authorization, outside any existing transaction. The operation
     * must use this transaction's datasource, and construct the exact HTTP response before returning.
     * Target includes the path and query; query and body differences conflict within one operation.
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
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Idempotency boundary must start outside an existing transaction");
        }
        String operationHash = digest(method, target.split("\\?", 2)[0]);
        String keyHash = digest(key);
        String requestHash = digest(method, target, Base64.getEncoder().encodeToString(body));
        try {
            return transaction.execute(tx -> {
                int inserted = jdbc.update("INSERT INTO idempotency_record (user_id, operation_hash, key_hash, request_hash) "
                                + "VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                        userId, operationHash, keyHash, requestHash);
                var rows = jdbc.query("SELECT request_hash, status, content_type, response_body, "
                                + "expires_at IS NOT NULL AND expires_at <= clock_timestamp() AS expired "
                                + "FROM idempotency_record WHERE user_id = ? AND operation_hash = ? AND key_hash = ? FOR UPDATE",
                        (rs, row) -> new Stored(rs.getString("request_hash"), (Integer) rs.getObject("status"),
                                rs.getString("content_type"), rs.getBytes("response_body"), rs.getBoolean("expired")),
                        userId, operationHash, keyHash);
                if (rows.size() != 1) throw new IllegalStateException("Idempotency record disappeared");
                Stored stored = rows.getFirst();
                if (stored.status() != null && !stored.expired()) {
                    if (!requestHash.equals(stored.requestHash())) throw new Rejected(Reason.CONFLICT);
                    return new Response(stored.status(), stored.contentType(), stored.body());
                }
                // A committed incomplete row cannot be produced by this component; fail closed if one exists.
                if (stored.status() == null && inserted != 1) {
                    throw new Rejected(Reason.STORE_UNAVAILABLE);
                }
                Response response = Objects.requireNonNull(operation.get(), "operation response");
                int updated = jdbc.update("UPDATE idempotency_record SET request_hash = ?, status = ?, content_type = ?, "
                                + "response_body = ?, expires_at = clock_timestamp() + interval '24 hours' "
                                + "WHERE user_id = ? AND operation_hash = ? AND key_hash = ?",
                        requestHash, response.status(), response.contentType(), response.body(), userId, operationHash, keyHash);
                if (updated != 1) throw new IllegalStateException("Idempotency record disappeared");
                return response;
            });
        } catch (DataAccessException | TransactionException e) {
            throw new Rejected(Reason.STORE_UNAVAILABLE, e);
        }
    }

    private static String digest(String... values) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                md.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
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
    private record Stored(String requestHash, Integer status, String contentType, byte[] body, boolean expired) { }
    public enum Reason { INVALID_KEY, CONFLICT, IN_PROGRESS, STORE_UNAVAILABLE }
    public static final class Rejected extends RuntimeException {
        private final Reason reason;
        public Rejected(Reason reason) { super(reason.name()); this.reason = reason; }
        public Rejected(Reason reason, Throwable cause) { super(reason.name(), cause); this.reason = reason; }
        public Reason reason() { return reason; }
    }
}
