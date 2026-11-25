# Transactional idempotency integration

`IdempotencyHttp` is an explicit synchronous controller helper, not interception. `@Idempotent` is only a marker; **no existing endpoint is wired or protected**. Apply it only after resolving endpoint-specific key requirements: R-LOCK-06 mandates seven operations (create partner, add follow-up, save plan, claim, close, register attachment and advance), while BE-SEC-006 permits missing keys on other POSTs. Saving a plan is PUT despite general rules saying PUT/DELETE do not require a key. No OpenAPI file is currently present to resolve this conflict.

Example controller shape (names illustrative, not an implemented endpoint):

```java
@PostMapping("/partners/{id}/example")
ResponseEntity<byte[]> write(HttpServletRequest request, @PathVariable long id,
                             @RequestBody byte[] body) {
    return idempotencyHttp.execute(request, body,
        () -> partnerAuthorization.requireWrite(AuthContext.get().userId(), id),
        () -> {
            // Parse/validate body, write through the same PostgreSQL transaction manager,
            // then serialize the final API envelope before returning.
            byte[] json = serialize(service.write(id, body));
            return new IdempotencyService.Response(201, "application/json", json,
                    "/api/v1/partners/" + id, null);
        });
}
```

Authentication must run on **every request**, as must the supplied resource authorization callback, including replays. Never put authorization only inside the operation callback: it is skipped on replay. The callback should check current permission and throw on denial. The helper requires an authenticated `AuthContext`, and invokes authorization before inspecting the key or store. Ensure the endpoint is protected by the existing JWT filter and role controls. If authorization must be atomic with concurrent permission revocation, this preflight check is insufficient; design appropriate locking/authorization transaction semantics rather than using this helper as-is.

Supply exact raw body bytes (parse within the operation as needed) and the unchanged request URI/query; the helper extracts method and raw URI/query. Exactly one visible-ASCII `Idempotency-Key` of 1–200 characters, excluding comma (to reject combined header values), is required. The helper maps invalid keys to HTTP 200/code 1001, different request or in-progress to HTTP 200/code 3001, and store failure to HTTP 500/code 5000; exceptions never disclose database details. Authentication failure is 401/code 1002. Other business errors use existing handling. For optional-key endpoints, `executeOptional` bypasses idempotency only when the key is absent, after authorization; the operation must arrange its normal transaction in that branch. Malformed or duplicate keys never bypass.

Call outside any existing transaction. The component starts its own READ COMMITTED transaction; all writes must use the same datasource/transaction manager, and the complete response must be serialized in the callback before commit. Throw on failure so writes roll back; deliberately returned errors are stored and replayed. Status, content type, exact body, and optional single `Location` and `ETag` values are persisted and replayed. Other headers, including Set-Cookie, authorization, hop-by-hop, and dynamically generated headers, are never stored. Do not use if the endpoint requires additional response headers, streaming, async, multipart, external side effects, independent transactions, or another database; use an appropriate separate protocol such as an outbox.

Scope is user ID + method and path (without query) + key; method/path/key are hashed and method, full target including raw query, and exact body fingerprinted. Different query/body with an unexpired key conflicts. Concurrent calls block on PostgreSQL locks; a commit yields replay/conflict, a rollback permits a contender to execute. `IN_PROGRESS` is reserved, normally not emitted. Lock timeouts and outages fail closed; commit outcome may be uncertain, so retry the same key. Results expire 24 hours after completion; retry after expiry can create another write. Expired rows are reused on demand, with no cleanup job yet. No keys or payloads are logged here.

This component uses PostgreSQL only. Application Redis defaults to database 9 and can be overridden by environment configuration. Switching an existing deployment from database 0 to 9 separates its token blacklist: previously revoked but unexpired tokens could become usable. Migrate blacklist entries or otherwise invalidate active tokens before switching. Endpoint wiring, production cleanup/retention, and contract reconciliation remain required before claiming endpoint-level AC-INFRA-10 or AC-FOLLOW-07.
