package com.easyshop.cart.repository;

import com.easyshop.cart.config.CartProperties;
import com.easyshop.cart.dto.CartDtos.CartItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Repository over the Redis Hash cart model: key cart:{userId} (or
 * cart:guest:{token}), one hash field per productId, value = CartItem as JSON.
 *
 * Why a hand-rolled repository over Spring Data Redis's @RedisHash
 * repositories: @RedisHash maps an entity to a hash PLUS maintains
 * secondary index sets, and its partial-update story is weak - you
 * generally save the whole object back. Our access pattern is exactly
 * Redis-native hash operations (HSET one field, HDEL one field,
 * HGETALL) and nothing else, so the abstraction would obscure the very
 * atomicity properties (per-field HSET/HDEL) that justified the hash
 * model in the first place. When the abstraction hides the property you
 * chose the technology for, drop the abstraction.
 *
 * SLIDING TTL: every mutation re-arms the expiry for that cart's key-space
 * (see CartProperties - 30d authenticated, 7d guest). An actively tended cart
 * lives indefinitely; an abandoned one silently evaporates - no cleanup job,
 * no cron, no tombstones. This is the lifecycle argument for
 * Redis-as-primary-store made concrete.
 *
 * Reads deliberately do NOT re-arm the TTL: "abandoned" is defined as
 * not-modified, so the expiry clock measures time since the last real change
 * rather than time since the last page view. Refreshing on read would turn
 * every cart GET into a write and would keep a cart alive forever for anyone
 * who merely has the page open.
 */
@Repository
public class CartRepository {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final CartProperties cartProperties;

    public CartRepository(StringRedisTemplate redis, ObjectMapper objectMapper,
                          CartProperties cartProperties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.cartProperties = cartProperties;
    }

    public List<CartItem> findAll(CartKey cart) {
        Map<Object, Object> entries = redis.opsForHash().entries(cart.redisKey());
        return entries.values().stream()
                .map(v -> deserialize((String) v))
                .sorted((a, b) -> a.addedAt().compareTo(b.addedAt()))
                .toList();
    }

    public CartItem find(CartKey cart, UUID productId) {
        Object value = redis.opsForHash().get(cart.redisKey(), productId.toString());
        return value == null ? null : deserialize((String) value);
    }

    /**
     * HSET is atomic per field: two tabs adding DIFFERENT products
     * interleave safely with no lost updates. Two tabs writing the SAME
     * product is last-write-wins - accepted deliberately (see the Phase 8
     * proportionality rationale vs inventory's optimistic locking).
     */
    public void put(CartKey cart, CartItem item) {
        redis.opsForHash().put(cart.redisKey(), item.productId().toString(), serialize(item));
        touch(cart);
    }

    public void remove(CartKey cart, UUID productId) {
        redis.opsForHash().delete(cart.redisKey(), productId.toString());
        touch(cart);
    }

    public void clear(CartKey cart) {
        redis.delete(cart.redisKey());
    }

    /** Remaining lifetime of a cart, or null if the key is absent or has no expiry. */
    public Duration timeToLive(CartKey cart) {
        Long seconds = redis.getExpire(cart.redisKey());
        return (seconds == null || seconds < 0) ? null : Duration.ofSeconds(seconds);
    }

    /**
     * Re-arms the expiry for this cart's key-space.
     *
     * Note this is a no-op when the key does not exist, which is exactly what
     * we want after removing the LAST item: Redis deletes a hash once its final
     * field is gone, so there is no empty husk left behind to expire, and EXPIRE
     * against the missing key simply returns false rather than resurrecting it.
     */
    private void touch(CartKey cart) {
        redis.expire(cart.redisKey(), cartProperties.ttlFor(cart));
    }

    private String serialize(CartItem item) {
        try {
            return objectMapper.writeValueAsString(item);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize cart item", e);
        }
    }

    private CartItem deserialize(String json) {
        try {
            return objectMapper.readValue(json, CartItem.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize cart item", e);
        }
    }
}
