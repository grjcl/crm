package com.crm.api.idempotency;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Real PostgreSQL tests. Set IDEMPOTENCY_TEST_JDBC_URL, IDEMPOTENCY_TEST_DB_USER and
 * IDEMPOTENCY_TEST_DB_PASSWORD to a maintenance database where the user may create databases. */
class IdempotencyServiceTest {
    private static final byte[] BODY = "{\"name\":\"hotel\"}".getBytes(StandardCharsets.UTF_8);
    private String database;
    private boolean databaseCreated;
    private String url;
    private String testUrl;
    private String user;
    private String password;
    private JdbcTemplate jdbc;
    private IdempotencyService service;

    @BeforeEach void setup() throws Exception {
        url = System.getenv("IDEMPOTENCY_TEST_JDBC_URL");
        user = System.getenv("IDEMPOTENCY_TEST_DB_USER");
        password = System.getenv("IDEMPOTENCY_TEST_DB_PASSWORD");
        assumeTrue(url != null && user != null && password != null, "PostgreSQL integration test not configured");
        database = "t_idempotency_" + UUID.randomUUID().toString().replace("-", "");
        int slash = url.lastIndexOf('/');
        if (!url.startsWith("jdbc:postgresql://") || slash < "jdbc:postgresql://".length() || url.indexOf('?') >= 0) {
            throw new IllegalArgumentException("Use a simple JDBC PostgreSQL URL without query parameters");
        }
        testUrl = url.substring(0, slash + 1) + database;
        try (Connection c = DriverManager.getConnection(url, user, password); var s = c.createStatement()) {
            s.execute("CREATE DATABASE " + database);
            databaseCreated = true;
        }
        DataSource ds = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                return DriverManager.getConnection(testUrl, user, password);
            }
            @Override public Connection getConnection(String username, String pwd) throws SQLException {
                throw new SQLException("Explicit credentials not supported");
            }
        };
        jdbc = new JdbcTemplate(ds);
        java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
        while (root != null && !java.nio.file.Files.exists(root.resolve("shared/db/V4__idempotency_record.sql"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Cannot locate idempotency migration");
        jdbc.execute(java.nio.file.Files.readString(root.resolve("shared/db/V4__idempotency_record.sql")));
        jdbc.execute("CREATE TABLE t_business (id integer PRIMARY KEY)");
        service = new IdempotencyService(jdbc, new DataSourceTransactionManager(ds));
    }

    @AfterEach void cleanup() throws Exception {
        if (databaseCreated) {
            if (!database.matches("t_idempotency_[0-9a-f]{32}")) throw new IllegalStateException("Invalid test database name");
            try (Connection c = DriverManager.getConnection(url, user, password); var s = c.createStatement()) {
                s.execute("DROP DATABASE " + database);
            }
        }
    }

    private IdempotencyService.Response invoke(String key, String target, byte[] body,
                                               java.util.function.Supplier<IdempotencyService.Response> operation) {
        return service.execute(1, key, "POST", target, body, operation);
    }
    private IdempotencyService.Response write(int id) {
        jdbc.update("INSERT INTO t_business VALUES (?)", id);
        return new IdempotencyService.Response(201, "application/json", BODY);
    }
    private int count() { return jdbc.queryForObject("SELECT count(*) FROM t_business", Integer.class); }
    private void assertResponse(IdempotencyService.Response response) {
        assertEquals(201, response.status());
        assertEquals("application/json", response.contentType());
        assertArrayEquals(BODY, response.body());
    }

    @Test void firstAndReplayPreserveResponseAndRunOnce() {
        assertResponse(invoke("key", "/hotels?x=1", BODY, () -> write(1)));
        assertResponse(invoke("key", "/hotels?x=1", BODY, () -> fail("replay executed")));
        assertEquals(1, count());
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("SELECT expires_at > clock_timestamp() + interval '23 hours 59 minutes' AND expires_at <= clock_timestamp() + interval '24 hours' FROM idempotency_record", Boolean.class)));
    }

    @Test void responseHeadersPersistAcrossReplayIncludingNulls() {
        byte[] body = "created".getBytes(StandardCharsets.UTF_8);
        var original = invoke("headers", "/hotels", BODY, () -> {
            write(1);
            return new IdempotencyService.Response(201, "text/plain; charset=UTF-8", body,
                    "/api/v1/hotels/1?view=full", "W/\"version-1\"");
        });
        var replay = invoke("headers", "/hotels", BODY, () -> fail("replay executed"));
        for (var response : new IdempotencyService.Response[] {original, replay}) {
            assertEquals(201, response.status());
            assertEquals("text/plain; charset=UTF-8", response.contentType());
            assertArrayEquals(body, response.body());
            assertEquals("/api/v1/hotels/1?view=full", response.location());
            assertEquals("W/\"version-1\"", response.etag());
        }
        invoke("no-headers", "/hotels", BODY, () -> write(2));
        var withoutHeaders = invoke("no-headers", "/hotels", BODY, () -> fail("replay executed"));
        assertNull(withoutHeaders.location());
        assertNull(withoutHeaders.etag());
        assertEquals(2, count());
    }

    @Test void userAndOperationScopeAndInvalidKey() {
        assertEquals(IdempotencyService.Reason.INVALID_KEY, assertThrows(IdempotencyService.Rejected.class,
                () -> invoke(" ", "/hotels", BODY, () -> fail("invalid key executed"))).reason());
        invoke("key", "/hotels", BODY, () -> write(1));
        service.execute(2, "key", "POST", "/hotels", BODY, () -> write(2));
        invoke("key", "/other", BODY, () -> write(3));
        service.execute(1, "key", "PUT", "/hotels", BODY, () -> write(4));
        assertEquals(4, count());
    }

    @Test void sameKeyAcrossUsersAndDifferentKeysKeepIndependentResponses() {
        byte[] firstBody = "first user".getBytes(StandardCharsets.UTF_8);
        byte[] secondBody = "second user".getBytes(StandardCharsets.UTF_8);
        byte[] thirdBody = "different key".getBytes(StandardCharsets.UTF_8);
        service.execute(1, "key", "POST", "/hotels", BODY, () -> {
            write(1); return new IdempotencyService.Response(201, "text/plain", firstBody);
        });
        service.execute(2, "key", "POST", "/hotels", BODY, () -> {
            write(2); return new IdempotencyService.Response(202, "text/plain", secondBody);
        });
        service.execute(1, "other-key", "POST", "/hotels", BODY, () -> {
            write(3); return new IdempotencyService.Response(203, "text/plain", thirdBody);
        });
        var first = service.execute(1, "key", "POST", "/hotels", BODY, () -> fail("replay executed"));
        var second = service.execute(2, "key", "POST", "/hotels", BODY, () -> fail("replay executed"));
        var third = service.execute(1, "other-key", "POST", "/hotels", BODY, () -> fail("replay executed"));
        assertEquals(201, first.status());
        assertArrayEquals(firstBody, first.body());
        assertEquals(202, second.status());
        assertArrayEquals(secondBody, second.body());
        assertEquals(203, third.status());
        assertArrayEquals(thirdBody, third.body());
        assertEquals(3, count());
    }

    @Test void changedBodyOrQueryConflictsWithoutRunningBusiness() {
        invoke("key", "/hotels?x=1", BODY, () -> write(1));
        for (var target : new String[] {"/hotels?x=1", "/hotels?x=2"}) {
            byte[] body = target.endsWith("1") ? new byte[] {1} : BODY;
            var error = assertThrows(IdempotencyService.Rejected.class,
                    () -> invoke("key", target, body, () -> fail("conflicting request executed")));
            assertEquals(IdempotencyService.Reason.CONFLICT, error.reason());
        }
        assertEquals(1, count());
    }

    @Test void conflictDoesNotAffectOtherUsersOrKeys() {
        invoke("key", "/hotels?x=1", BODY, () -> write(1));
        byte[] changedBody = "changed".getBytes(StandardCharsets.UTF_8);
        var rejection = assertThrows(IdempotencyService.Rejected.class,
                () -> invoke("key", "/hotels?x=1", changedBody, () -> fail("conflict executed")));
        assertEquals(IdempotencyService.Reason.CONFLICT, rejection.reason());
        service.execute(2, "key", "POST", "/hotels?x=1", changedBody, () -> write(2));
        invoke("other-key", "/hotels?x=1", changedBody, () -> write(3));
        assertResponse(invoke("key", "/hotels?x=1", BODY, () -> fail("original replay executed")));
        assertEquals(3, count());
    }

    @Test void rollbackDiscardsBothBusinessWriteAndClaim() {
        assertThrows(IllegalStateException.class, () -> invoke("key", "/hotels", BODY, () -> {
            write(1);
            throw new IllegalStateException("failed before commit");
        }));
        assertEquals(0, count());
        assertResponse(invoke("key", "/hotels", BODY, () -> write(1)));
        assertEquals(1, count());
    }

    @Test void retryAfterCommittedResponseWasLostReplays() {
        invoke("key", "/hotels", BODY, () -> write(1)); // caller loses the response after commit
        assertResponse(invoke("key", "/hotels", BODY, () -> fail("retry executed")));
        assertEquals(1, count());
    }

    @Test void expiredRecordCanBeReused() {
        invoke("key", "/hotels", BODY, () -> write(1));
        jdbc.update("UPDATE idempotency_record SET expires_at = clock_timestamp() - interval '1 second'");
        assertResponse(invoke("key", "/hotels", BODY, () -> write(2)));
        assertEquals(2, count());
    }

    @Test void concurrentRequestsExecuteOnlyOnce() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> invoke("key", "/hotels", BODY, () -> {
                calls.incrementAndGet();
                var response = write(1);
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("timed out"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                return response;
            }));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                var second = executor.submit(() -> invoke("key", "/hotels", BODY, () -> {
                    calls.incrementAndGet(); return write(2);
                }));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                boolean waiting = false;
                while (System.nanoTime() < deadline && !waiting) {
                    waiting = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_stat_activity "
                            + "WHERE datname = current_database() AND wait_event_type = 'Lock' "
                            + "AND query LIKE 'INSERT INTO idempotency_record%')", Boolean.class));
                    if (!waiting) Thread.sleep(20);
                }
                assertTrue(waiting, "second request did not reach the competing database lock");
                release.countDown();
                assertResponse(first.get(15, TimeUnit.SECONDS));
                assertResponse(second.get(15, TimeUnit.SECONDS));
                assertEquals(1, calls.get());
                assertEquals(1, count());
            } finally { release.countDown(); }
        }
    }
}
