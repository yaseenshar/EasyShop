package com.easyshop.cart.repository;

import com.easyshop.cart.dto.CartDtos.CartItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Repository over the Redis Hash cart model: key cart:{userId}, one hash
 * field per productId, value = CartItem as JSON.
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
 * SLIDING TTL: every mutation re-arms a 30-day expiry. An actively
 * tended cart lives indefinitely; an abandoned one silently evaporates -
 * no cleanup job, no cron, no tombstones. This is the lifecycle argument
 * for Redis-as-primary-store made concrete.
 */
@Repository
public class CartRepository {

    private static final Duration CART_TTL = Duration.ofDays(30);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public CartRepository(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    private String key(UUID userId) {
        return "cart:" + userId;
    }

    public List<CartItem> findAll(UUID userId) {
        Map<Object, Object> entries = redis.opsForHash().entries(key(userId));
        return entries.values().stream()
                .map(v -> deserialize((String) v))
                .sorted((a, b) -> a.addedAt().compareTo(b.addedAt()))
                .toList();
    }

    public CartItem find(UUID userId, UUID productId) {
        Object value = redis.opsForHash().get(key(userId), productId.toString());
        return value == null ? null : deserialize((String) value);
    }

    /**
     * HSET is atomic per field: two tabs adding DIFFERENT products
     * interleave safely with no lost updates. Two tabs writing the SAME
     * product is last-write-wins - accepted deliberately (see the Phase 8
     * proportionality rationale vs inventory's optimistic locking).
     */
    public void put(UUID userId, CartItem item) {
        redis.opsForHash().put(key(userId), item.productId().toString(), serialize(item));
        touch(userId);
    }

    public void remove(UUID userId, UUID productId) {
        redis.opsForHash().delete(key(userId), productId.toString());
        touch(userId);
    }

    public void clear(UUID userId) {
        redis.delete(key(userId));
    }

    private void touch(UUID userId) {
        redis.expire(key(userId), CART_TTL);
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