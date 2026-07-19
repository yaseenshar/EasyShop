package com.easyshop.payment.gateway;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed idempotency lock, as designed in Step 4.1. Two distinct TTLs
 * are used deliberately:
 *
 *   - LOCK_TTL (short, 5 min): protects against a request that crashed
 *     mid-processing without ever writing a result - after 5 minutes, a
 *     retry is allowed to proceed again rather than being stuck forever
 *     behind a dead lock.
 *   - RESULT_TTL (long, 24h): once we have a real result (succeeded or
 *     failed), we cache it for a full day so that ANY retry within that
 *     window - even a legitimate client retry hours later after a network
 *     partition healed - gets back the original result instead of a second
 *     attempt to charge the card.
 */
@Service
public class IdempotencyLockService {

    private static final Duration LOCK_TTL = Duration.ofMinutes(5);
    private static final Duration RESULT_TTL = Duration.ofHours(24);
    private static final String KEY_PREFIX = "idempotency:payment:";
    private static final String PROCESSING_MARKER = "PROCESSING";

    private final StringRedisTemplate redisTemplate;

    public IdempotencyLockService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Attempts to acquire the lock for this idempotency key.
     *
     * @return true if this caller now owns the lock and should proceed with
     *         the actual payment gateway call; false if another request
     *         already holds it (caller should check getCachedResult next).
     */
    public boolean tryAcquireLock(String idempotencyKey) {
        String key = KEY_PREFIX + idempotencyKey;
        // setIfAbsent = the Java client's wrapper around Redis SET key val NX EX -
        // a single atomic round-trip, no separate GET-then-SET race window.
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, PROCESSING_MARKER, LOCK_TTL);
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * Called by a request that LOST the race in tryAcquireLock. Returns the
     * cached final result if processing has already completed, or empty if
     * the original request is still in flight (caller should signal 409 and
     * tell the client to retry shortly).
     */
    public Optional<String> getCachedResult(String idempotencyKey) {
        String key = KEY_PREFIX + idempotencyKey;
        String value = redisTemplate.opsForValue().get(key);
        if (value == null || PROCESSING_MARKER.equals(value)) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    /**
     * Called by the lock-owning request once the real result (transaction ID
     * or failure reason) is known. Overwrites the PROCESSING marker with the
     * actual outcome and extends the TTL to the longer RESULT_TTL.
     */
    public void storeResult(String idempotencyKey, String resultJson) {
        String key = KEY_PREFIX + idempotencyKey;
        redisTemplate.opsForValue().set(key, resultJson, RESULT_TTL);
    }

    public void releaseLockOnFailure(String idempotencyKey) {
        // If something throws before storeResult() is reached, release the
        // lock immediately rather than waiting out the full 5-minute TTL -
        // this lets a legitimate retry proceed sooner after a transient error.
        redisTemplate.delete(KEY_PREFIX + idempotencyKey);
    }
}