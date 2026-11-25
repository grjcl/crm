package com.crm.api.idempotency;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.crm.api.common.BizException;
import com.crm.api.security.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class IdempotencyHttpTest {
    private final IdempotencyService service = mock(IdempotencyService.class);
    private final IdempotencyHttp http = new IdempotencyHttp(service);
    private final byte[] body = "{}".getBytes();

    @AfterEach void clear() { AuthContext.clear(); }
    private void login() { AuthContext.set(new AuthContext.AuthUser(42L, "SALES", "j", null, null, false)); }
    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("POST", "/api/v1/test");
        request.addHeader("Idempotency-Key", "key");
        return request;
    }

    @Test void authorizationRunsBeforeEveryReplayAndDenialStopsLookup() {
        login();
        var response = new IdempotencyService.Response(201, "application/json", body, "/x", "\"v1\"");
        when(service.execute(anyLong(), anyString(), anyString(), anyString(), any(), any())).thenReturn(response);
        var first = http.execute(request(), body, () -> {}, () -> fail("service mock must not execute"));
        assertEquals("/x", first.getHeaders().getFirst("Location"));
        assertEquals("\"v1\"", first.getHeaders().getFirst("ETag"));
        assertThrows(BizException.class, () -> http.execute(request(), body,
                () -> { throw new BizException(1003, "denied", 403); }, () -> response));
        verify(service, times(1)).execute(anyLong(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test void optionalAbsentBypassesButDuplicateFails() {
        login();
        var request = new MockHttpServletRequest("POST", "/x");
        var response = new IdempotencyService.Response(200, "application/json", body);
        assertEquals(200, http.executeOptional(request, body, () -> {}, () -> response).getStatusCode().value());
        request.addHeader("Idempotency-Key", "a");
        request.addHeader("Idempotency-Key", "b");
        assertEquals(1001, assertThrows(BizException.class,
                () -> http.executeOptional(request, body, () -> {}, () -> response)).getCode());
        verifyNoInteractions(service);
    }

    @Test void mapsErrorsWithoutLeakingDetails() {
        login();
        when(service.execute(anyLong(), anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new IdempotencyService.Rejected(IdempotencyService.Reason.STORE_UNAVAILABLE));
        var ex = assertThrows(BizException.class, () -> http.execute(request(), body, () -> {}, () -> null));
        assertEquals(500, ex.getHttpStatus());
        assertEquals(5000, ex.getCode());
    }

    @Test void mapsInvalidAndConflict() {
        login();
        when(service.execute(anyLong(), anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new IdempotencyService.Rejected(IdempotencyService.Reason.INVALID_KEY))
                .thenThrow(new IdempotencyService.Rejected(IdempotencyService.Reason.CONFLICT));
        var invalid = assertThrows(BizException.class, () -> http.execute(request(), body, () -> {}, () -> null));
        var conflict = assertThrows(BizException.class, () -> http.execute(request(), body, () -> {}, () -> null));
        assertEquals  (1001, invalid.getCode());
        assertEquals(200, invalid.getHttpStatus());
        assertEquals(3001, conflict.getCode());
        assertEquals(200, conflict.getHttpStatus());
    }

    @Test void rejectsUnauthenticatedBeforeAuthorization() {
        assertEquals(401, assertThrows(BizException.class,
                () -> http.execute(request(), body, () -> fail("authorization"), () -> null)).getHttpStatus());
        verifyNoInteractions(service);
    }
}
