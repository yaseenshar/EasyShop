package com.easyshop.common.idempotency; // align with common-lib layout

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an HTTP handler method as idempotent. OPT-IN by design: the engine is
 * inert everywhere this annotation is absent, so it never silently changes the
 * semantics of an endpoint nobody reviewed. Grep-able, per-endpoint, explicit.
 *
 * The client supplies an Idempotency-Key header (an unguessable UUID it keeps
 * across retries — the SAME key on retry is what makes the retry safe, exactly
 * as the checkout flow already requires in the SPA). The engine turns that key
 * into a Redis SET NX EX lock, then a cached-response record.
 *
 * WHERE THIS FITS (§4.8): this is the HTTP door. It does NOT replace the
 * Kafka-side idempotency in payment/notification (a web interceptor cannot see
 * a Kafka message) — those consume the same IdempotencyStore primitive directly.
 * On checkout it sits IN FRONT OF the existing DB unique constraint: Redis is
 * the fast path, the constraint stays as the correctness backstop. Same
 * two-layer shape as payment-service (§4.8), now applied to checkout.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /**
     * If true (default), a missing/blank Idempotency-Key is a 400. Mutating
     * money-adjacent endpoints should require it so an accidental double-submit
     * cannot slip through as two distinct un-keyed requests. Set false for
     * best-effort endpoints where dedup is a bonus, not a guarantee.
     */
    boolean required() default true;

    /**
     * Short TTL for the IN-PROGRESS marker. Must exceed the slowest legitimate
     * processing time for this endpoint, but stay short so a crash BETWEEN
     * lock-acquire and response-persist self-heals (a stuck lock frees itself
     * instead of blocking every retry until a long TTL expires). Seconds.
     */
    long inProgressTtlSeconds() default 60;

    /**
     * Long TTL for the COMPLETED response record — the window in which a retry
     * replays the original response verbatim. 24h matches payment-service's
     * existing result-cache choice (§4.8).
     */
    long completedTtlSeconds() default 86_400;

    /**
     * Behaviour when Redis is unreachable:
     *  - OPEN  (default): process the request WITHOUT idempotency protection,
     *          log a warning. Correct when a backstop exists (checkout's DB
     *          constraint; payment's own downstream idempotency) — availability
     *          over strict dedup.
     *  - CLOSED: reject with 503. Correct only for an endpoint whose duplicate
     *          is catastrophic AND has no backstop.
     */
    RedisFailureMode onRedisFailure() default RedisFailureMode.OPEN;

    enum RedisFailureMode { OPEN, CLOSED }
}