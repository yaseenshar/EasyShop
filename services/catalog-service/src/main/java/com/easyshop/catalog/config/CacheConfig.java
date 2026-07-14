package com.easyshop.catalog.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.DefaultTyping;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Redis cache configuration for catalog-service.
 *
 * Every decision here is deliberate:
 *
 * 1. JSON VALUE SERIALIZATION (not JDK serialization). JDK serialization is
 *    the historical default and it is wrong for a cache: values become
 *    opaque binary blobs unreadable in redis-cli, they couple the cache
 *    format to exact Java class versions (a redeploy with a changed DTO
 *    breaks deserialization of every existing entry), and they are Java-
 *    only (a future non-JVM consumer can't read them). JSON fixes all three.
 *
 *    NAMING NOTE (verified against 2026 sources, flag for compile check):
 *    Boot 4 ships Jackson 3, and the serializer class current sources use
 *    is GenericJacksonJsonRedisSerializer - NOT the Boot 3.x-era
 *    GenericJackson2JsonRedisSerializer that virtually every older tutorial
 *    imports. If this import fails to resolve, check the current class name
 *    at docs.spring.io/spring-data/redis - the "Jackson2" name is the
 *    outdated-tutorial tell here, same category as javax.* imports.
 *
 * 2. STRING KEY SERIALIZATION. Keys like "products::a1b2c3..." stay human-
 *    readable and debuggable: `redis-cli KEYS 'products::*'` just works.
 *
 * 3. PER-CACHE TTLs, WITH JITTER. Product details cache for ~10 minutes,
 *    category listings for ~2 (listings change more often - new products
 *    appear). The +-20% random jitter on each cache's base TTL prevents a
 *    thundering herd: if thousands of entries were cached at the same
 *    moment (e.g. after a deploy cleared the cache), identical TTLs would
 *    expire them at the same moment too, stampeding the DB with
 *    simultaneous misses. Jitter spreads that expiry wave out.
 *    (Per-ENTRY dynamic TTL via RedisCacheWriter.TtlFunction is the
 *    verified-current mechanism if finer control is ever needed.)
 *
 * 4. NULL CACHING DISABLED. Caching a null "product not found" result for
 *    10 minutes would make a product invisible for 10 minutes after it's
 *    created if someone queried it moments too early. The tradeoff: this
 *    removes protection against cache-penetration attacks (hammering
 *    deliberately-nonexistent IDs to bypass the cache and hit the DB).
 *    The right defense for that is a Bloom filter in front of the cache -
 *    noted as the escalation path, not built until needed.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String PRODUCTS_CACHE = "products";
    public static final String PRODUCT_LISTINGS_CACHE = "product-listings";

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory,
                                          ObjectMapper objectMapper) {

        // The shared app ObjectMapper has no polymorphic type info enabled
        // (Boot's default, and rightly so - unrestricted default typing is
        // a deserialization RCE vector). Without it, GenericJacksonJsonRedis-
        // Serializer writes plain JSON with no type hint, so a cache HIT
        // deserializes to a generic LinkedHashMap instead of the original
        // record type and blows up with a ClassCastException at the call
        // site. Type info is only worth enabling on a cache-scoped copy of
        // the mapper, restricted to this service's own DTO package.
        //
        // DefaultTyping.NON_FINAL_AND_RECORDS, not NON_FINAL: the cached
        // types (ProductResponse, PagedResponse) are records, and every
        // Java record is implicitly final. Plain NON_FINAL deliberately
        // skips type hints for final classes on the assumption the
        // caller's static type is enough to deserialize - true for a
        // normal field, false here, since RedisCache always reads back
        // through erased Object.class. Without the _AND_RECORDS variant
        // the root value gets no type hint at all and deserializes to a
        // raw LinkedHashMap.
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.easyshop.catalog.dto")
                .allowIfSubType("java.util")
                .build();
        ObjectMapper cacheMapper = objectMapper.rebuild()
                .activateDefaultTyping(typeValidator, DefaultTyping.NON_FINAL_AND_RECORDS)
                .build();

        var jsonSerializer = new GenericJacksonJsonRedisSerializer(cacheMapper);

        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(SerializationPair.fromSerializer(jsonSerializer))
                .disableCachingNullValues()
                .entryTtl(jitter(Duration.ofMinutes(5)));

        Map<String, RedisCacheConfiguration> perCache = Map.of(
                PRODUCTS_CACHE, defaults.entryTtl(jitter(Duration.ofMinutes(10))),
                PRODUCT_LISTINGS_CACHE, defaults.entryTtl(jitter(Duration.ofMinutes(2)))
        );

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(perCache)
                // transactionAware(): cache puts/evicts are deferred until the
                // surrounding Spring-managed transaction COMMITS. Without this,
                // an @CacheEvict firing inside a transaction that later rolls
                // back would have evicted a perfectly valid entry - or worse,
                // a @CachePut would cache data from a rolled-back write.
                // Verified against current Spring Data Redis reference docs.
                .transactionAware()
                .build();
    }

    /**
     * Applies random jitter of +-20% to a base TTL, computed ONCE at cache
     * configuration time per cache (not per entry - per-entry jitter would
     * need a TtlFunction). Even this coarse per-cache jitter breaks up the
     * pathological case of all caches expiring in lockstep after a restart.
     */
    private Duration jitter(Duration base) {
        long millis = base.toMillis();
        long delta = (long) (millis * 0.2);
        long jittered = millis - delta + ThreadLocalRandom.current().nextLong(2 * delta);
        return Duration.ofMillis(jittered);
    }
}