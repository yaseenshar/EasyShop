# idempotency-engine/ — Redis SET NX EX idempotency, extracted into one engine

**Ticket:** Build a Redis-based idempotency interceptor/filter (SET NX EX).

**Framing (important):** idempotency already exists in three places, deliberately
tuned to the cost of a duplicate (§4.8). This ticket does **not** add a fourth
bespoke copy — it extracts the SET NX EX primitive into `common-lib` so all
callers share one implementation, and adds the missing HTTP front door.

---

## Two doors, one primitive

Idempotency enters the system two ways, and a web filter only guards one:

| Door | Entry | Component |
|---|---|---|
| **HTTP** | client retries `POST /checkout` with an `Idempotency-Key` header | `IdempotencyInterceptor` (+ filter) — this ticket's engine |
| **Kafka** | saga redelivers `ChargePaymentCommand` | consumer calls `IdempotencyStore` directly on the message key |

`IdempotencyStore` is the shared SET NX EX + result-cache primitive both use. So
payment/notification keep their Kafka-side idempotency but stop hand-rolling the
Redis logic — the same "lift into common-lib" move as SagaMessages and the outbox
(§4.14). This preserves §4.8 proportionality instead of flattening it.

## Design decisions

**Filter + interceptor, not one or the other.** At filter time the handler isn't
resolved, so a filter can't see `@Idempotent`; an interceptor can, but can't
rewrite a committed response. So: the **filter** installs a replayable request
wrapper + a caching response wrapper (only when a mutating method carries the
key header — non-idempotent traffic pays nothing); the **interceptor** makes the
decision and replays. Rejected: pure filter with path-matching (loses per-method
opt-in, can't read the annotation), pure interceptor (can't buffer the response
for replay).

**`setIfAbsent(key, value, Expiration)`, not the `Duration` overload** — the
Duration form is deprecated as of Spring Data Redis 4.1 (Boot 4.1). A §2.1 tell:
every tutorial online still uses the deprecated one.

**Two TTLs, not one.** Short IN-PROGRESS marker (self-heals a crash between
lock and response), long COMPLETED record (replays for hours). One TTL can't do
both — this is the crash-safety core.

**Opt-in via `@Idempotent`.** Inert until an endpoint is annotated AND a request
sends the header. Dropping common-lib on the classpath changes nothing.

**Fail-open by default (§4.8 proportionality).** Redis down → process anyway and
let the backstop catch duplicates (checkout's DB constraint; payment's own
downstream idempotency). `onRedisFailure = CLOSED` for a no-backstop endpoint
whose duplicate is catastrophic.

**Layered on checkout, not replacing the constraint.** Redis is the fast path;
`UNIQUE(idempotency_key)` stays as the correctness backstop — the payment
two-layer shape (§4.8) now applied to checkout.

## Traps

1. **The request body can only be read once.** Fingerprinting the body in the
   interceptor would consume the stream before the controller. Fixed with
   `CachedBodyHttpServletRequest` (replays the buffered body) — Spring's
   `ContentCachingRequestWrapper` does NOT replay, so it can't be used here.
2. **Never cache non-2xx.** A cached 500 would replay the failure forever;
   `afterCompletion` releases the lock on 4xx/5xx so a real retry can retry.
3. **Same key + different body = 422, not a wrong replay.** The fingerprint
   catches a client reusing a key for a different operation; returning the first
   op's cached response silently would be a correctness bug.
4. **Scope keys per user + service.** `idem:{service}:{sub}:{key}` — prevents
   cross-user replay and cross-service collisions on shared Redis (§4.9).
5. **Bound the cached body (§7).** Redis also holds carts + cache on the 8 GB
   box; `maxCachedBodyBytes` caps replay memory. Over the cap, the engine dedups
   (lock + fingerprint) but doesn't replay the body.
6. **Ordering.** Filter runs after Spring Security (so `sub` is available) and
   before the DispatcherServlet; interceptor reads the annotation post-mapping.
   The filter's `copyBodyToResponse()` MUST run in `finally` or the client gets
   an empty body.

## Per-service changes

| Service | Change |
|---|---|
| **common-lib** | all engine classes + append `IdempotencyAutoConfiguration` to the `AutoConfiguration.imports` file; ensure `spring-boot-starter-data-redis` is available |
| **order-service** | `@Idempotent(required=true)` on checkout; keep the DB constraint; SPA sends the `Idempotency-Key` header |
| **payment / notification** | replace bespoke SET-NX code with `IdempotencyStore` calls in the consumer (behaviour identical, code shared) |
| gateway, others | nothing |

## Install order

1. common-lib: add classes, append the imports line, confirm Redis starter present.
2. order-service: annotate checkout; SPA sends the header (one line — it already
   generates and reuses the UUID, frontend-integration §2).
3. payment/notification: swap to `IdempotencyStore` (optional but recommended —
   removes the duplication this ticket exists to remove).
4. `mvn -pl common/common-lib install && mvn clean install && docker compose up -d --build`.
5. `./verify-idempotency.sh`, then re-run `verify-e2e.sh` (idempotency replay was
   already part of that) and the RBAC/resilience suites.

## Files

```
idempotency-engine/
  README.md
  verify-idempotency.sh
  common-lib/
    Idempotent.java                       # opt-in annotation
    IdempotencyStore.java                 # SET NX EX primitive (both doors)
    IdempotencyRecord.java                # cached response + fingerprint
    CachedBodyHttpServletRequest.java     # replayable body
    IdempotencyFilter.java                # gate + wrappers + flush
    IdempotencyInterceptor.java           # decide / replay / persist / release
    IdempotencyAttributes.java            # shared request-attribute keys
    IdempotencyProperties.java            # header name, namespace, body cap
    IdempotencyAutoConfiguration.java     # fleet-wide wiring (servlet + Redis)
  usage/
    UsageExamples.java                    # HTTP door + Kafka door
```

## §9 flags (verify, don't assume)

| Item | Concern | Fallback |
|---|---|---|
| `Expiration.from(Duration)` factory | exact factory name on Spring Data Redis 4.1 | `Expiration.seconds(d.toSeconds())` |
| `setIfAbsent(K,V,Expiration)` overload | confirm it resolves (Duration overload deprecated) | `RedisCallback` with `SetOption.SET_IF_ABSENT` (issue #2730 form) |
| `ContentCachingResponseWrapper` body available in `afterCompletion` | must read before the filter's `copyBodyToResponse()` | verified by verify step [2]; if empty, move persist into a `ResponseBodyAdvice` |
| Sealed `Begin` interface + record patterns | Java 25 supports it; confirm your compiler level | plain class hierarchy |