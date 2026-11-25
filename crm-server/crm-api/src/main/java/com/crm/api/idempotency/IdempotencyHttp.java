package com.crm.api.idempotency;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import com.crm.api.common.BizException;
import com.crm.api.common.ErrorCode;
import com.crm.api.security.AuthContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/** Explicit controller boundary. Authorization is evaluated even for a cached response. */
@Component
public class IdempotencyHttp {
    private final IdempotencyService service;

    public IdempotencyHttp(IdempotencyService service) {
        this.service = service;
    }

    /**
     * The caller must pass the exact received body bytes and an authorization check for the
     * requested resource. The operation must serialize its final response inside the transaction.
     * Do not call from a transactional controller or use for streaming responses.
     */
    public ResponseEntity<byte[]> execute(HttpServletRequest request, byte[] body,
            Runnable authorizeResource, Supplier<IdempotencyService.Response> operation) {
        return execute(request, body, authorizeResource, operation, true);
    }

    /** Missing key bypasses idempotency; the caller is responsible for normal write transactions. */
    public ResponseEntity<byte[]> executeOptional(HttpServletRequest request, byte[] body,
            Runnable authorizeResource, Supplier<IdempotencyService.Response> operation) {
        return execute(request, body, authorizeResource, operation, false);
    }

    private ResponseEntity<byte[]> execute(HttpServletRequest request, byte[] body,
            Runnable authorizeResource, Supplier<IdempotencyService.Response> operation, boolean required) {
        var user = AuthContext.get();
        if (user == null || user.userId() == null || user.userId() <= 0 || user.alreadyLoggedOut()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.getDefaultMessage(), 401);
        }
        Objects.requireNonNull(authorizeResource).run();
        List<String> keys = Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.isEmpty() && !required) return toHttp(Objects.requireNonNull(operation.get()));
        if (keys.size() != 1) throw invalidKey();
        String target = request.getRequestURI();
        if (request.getQueryString() != null) target += "?" + request.getQueryString();
        try {
            var result = service.execute(user.userId(), keys.getFirst(), request.getMethod(), target,
                    Objects.requireNonNull(body), operation);
            return toHttp(result);
        } catch (IdempotencyService.Rejected rejected) {
            throw switch (rejected.reason()) {
                case INVALID_KEY -> invalidKey();
                case CONFLICT -> new BizException(ErrorCode.DUPLICATE, "幂等键已用于不同请求");
                case IN_PROGRESS -> new BizException(ErrorCode.DUPLICATE, "请求正在处理，请稍后重试");
                case STORE_UNAVAILABLE -> new BizException(ErrorCode.INTERNAL_ERROR,
                        ErrorCode.INTERNAL_ERROR.getDefaultMessage(), 500);
            };
        }
    }

    private static ResponseEntity<byte[]> toHttp(IdempotencyService.Response result) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, result.contentType());
        if (result.location() != null) headers.set(HttpHeaders.LOCATION, result.location());
        if (result.etag() != null) headers.set(HttpHeaders.ETAG, result.etag());
        return ResponseEntity.status(result.status()).headers(headers).body(result.body());
    }

    private static BizException invalidKey() {
        return new BizException(ErrorCode.PARAM_INVALID, "Idempotency-Key 无效");
    }
}
