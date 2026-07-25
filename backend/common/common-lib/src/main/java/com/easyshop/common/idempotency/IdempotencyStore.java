package com.easyshop.common.idempotency; // align with common-lib layout

import java.time.Duration;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;

/**
 * The idempotency PRIMITIVE. This is the piece worth extracting: the SET NX EX
 * + result-cache logic that previously lived, duplicated, inside payment-service
 * and notification-service consumers (§4.8). One implementation, two callers:
 *
 *   - HTTP door: IdempotencyInterceptor (this bundle)
 *   - Kafka door: payment / notification consumers call begin()/complete()
 *                 directly on their message key (see usage/ examples)
 *
 * State machine per key:
 *   (absent) --SET NX--> IN_PROGRESS --complete()--> COMPLETED
 *                             |                          |
 *                          release()                (long TTL) --expiry--> (absent)
 *                             |
 *                     (short TTL self-heal on crash)
 *
 * Why two TTLs, not one: the IN_PROGRESS marker is short so a process that dies
 * between lock-acquire and complete() does not wedge the key; the COMPLETED
 * record is long so genuine retries replay for hours. A single TTL cannot serve
 * both — this is the crash-safety core of the design.
 */
public class IdempotencyStore {

    private static final String IN_PROGRESS_PREFIX = "P:"; // marker discriminator
    private static final String COMPLETED_PREFIX = "C:";   // JSON record follows

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public IdempotencyStore(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    /** Outcome of an acquire attempt. */
    public sealed interface Begin permits Begin.Acquired, Begin.InProgress, Begin.Completed {
        record Acquired() implements Begin {}
        record InProgress(String fingerprint) implements Begin {}
        record Completed(IdempotencyRecord record) implements Begin {}
    }

    /**
     * Atomic SET NX EX. Returns Acquired on first sight; otherwise reports the
     * current state so the caller can replay a completed response or reject an
     * in-flight duplicate.
     *
     * NOTE on the tiny race between the failed SET NX and the follow-up GET: if
     * the key expires in that window we treat it as a fresh Acquired. This is
     * safe (the operation simply runs again, which the backstop covers) and
     * rare. A Lua script collapses SET-NX-else-GET into one round trip if you
     * want to eliminate it — deferred; the two-call form is correct, just one
     * extra hop.
     */
    public Begin begin(String key, String fingerprint, Duration inProgressTtl) {
        String marker = IN_PROGRESS_PREFIX + fingerprint;

        // === SET key value NX EX ttl ===  (Expiration overload — the Duration
        // overload is DEPRECATED as of Spring Data Redis 4.1 / Boot 4.1.)
        Boolean won = redis.opsForValue()
                .setIfAbsent(key, marker, Expiration.from(inProgressTtl));

        if (Boolean.TRUE.equals(won)) {
            return new Begin.Acquired();
        }

        String existing = redis.opsForValue().get(key);
        if (existing == null) {
            return new Begin.Acquired(); // expired between calls — treat as new
        }
        if (existing.startsWith(IN_PROGRESS_PREFIX)) {
            return new Begin.InProgress(existing.substring(IN_PROGRESS_PREFIX.length()));
        }
        // COMPLETED record
        String json = existing.substring(COMPLETED_PREFIX.length());
        return new Begin.Completed(deserialize(json));
    }

    /** Overwrite the IN_PROGRESS marker with the final record under a long TTL. */
    public void complete(String key, IdempotencyRecord record, Duration completedTtl) {
        String value = COMPLETED_PREFIX + serialize(record);
        redis.opsForValue().set(key, value, Expiration.from(completedTtl));
    }

    /**
     * Free the lock immediately on a NON-cacheable outcome (5xx, or an error we
     * deliberately do not memoize) so a legitimate retry is allowed at once
     * rather than waiting out the in-progress TTL. Do NOT call this after a
     * cacheable success — complete() has already taken over the key.
     */
    public void release(String key) {
        redis.delete(key);
    }

    public Optional<IdempotencyRecord> peek(String key) {
        String existing = redis.opsForValue().get(key);
        if (existing == null || !existing.startsWith(COMPLETED_PREFIX)) return Optional.empty();
        return Optional.of(deserialize(existing.substring(COMPLETED_PREFIX.length())));
    }

    private String serialize(IdempotencyRecord r) {
        try { return mapper.writeValueAsString(r); }
        catch (Exception e) { throw new IllegalStateException("idempotency serialize failed", e); }
    }

    private IdempotencyRecord deserialize(String json) {
        try { return mapper.readValue(json, IdempotencyRecord.class); }
        catch (Exception e) { throw new IllegalStateException("idempotency deserialize failed", e); }
    }
}