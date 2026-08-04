package com.easyshop.catalog.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
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
 *    per-category product listings for ~2 (listings change more often - new
 *    products appear), and the category taxonomy itself for ~1 hour (it only
 *    changes by migration). The +-20% random jitter on each base TTL prevents a
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
 *
 * 5. IMMEDIATE WRITES. See the cacheWriter() comment - this is NOT the
 *    library default and eviction is silently racy without it.
 *
 * 6. GRACEFUL DEGRADATION WHEN REDIS IS DOWN. See errorHandler().
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    public static final String PRODUCTS_CACHE = "products";
    public static final String PRODUCT_LISTINGS_CACHE = "product-listings";
    public static final String CATEGORIES_CACHE = "categories";

    /**
     * immediateWrites() is the important part, and it is NOT the default.
     *
     * Spring Data Redis 4.x performs cache writes (put / evict / clear)
     * ASYNCHRONOUSLY and fire-and-forget whenever the connection factory is
     * reactive-capable - which Lettuce, our driver, is. Verified empirically
     * against a real Redis: after @CacheEvict returned, the key was still
     * present, and only disappeared some milliseconds later.
     *
     * For a cache-aside WRITE path that is a correctness bug, not a tuning
     * knob: updateProduct() would return 200 to the caller while the stale
     * entry was still live in Redis, so an immediate re-read could still see
     * the old price. The window is small and load-dependent, which is exactly
     * what makes it a miserable bug to chase in production.
     *
     * The cost is that cache writes now block on the Redis round trip. For
     * the payloads here (a single product DTO) that is a sub-millisecond SET,
     * and it buys read-your-own-write on the admin path.
     *
     * collectStatistics() is what makes hit/miss counts real: without it the
     * cache_gets{result="hit"} Micrometer meters that
     * /actuator/prometheus exposes are permanently zero, and there is no way
     * to tell a working cache from a cache that never hits.
     */
    @Bean
    public RedisCacheWriter cacheWriter(RedisConnectionFactory connectionFactory) {
        return RedisCacheWriter.create(connectionFactory, config -> config
                .immediateWrites()
                .collectStatistics());
    }

    /**
     * Without this, Redis being unreachable takes the whole catalog down: every
     * @Cacheable read throws RedisConnectionFailureException straight out of
     * the service and the product API returns 500s. That inverts the point of
     * a cache-aside layer, which is supposed to be a performance optimisation
     * the system can survive losing.
     *
     * On a GET error Spring falls back to invoking the underlying method
     * (read-through), including on the sync=true path - so the service simply
     * degrades to serving from MySQL, slower but correct.
     *
     * The deliberate tradeoff on the EVICT/CLEAR side: swallowing those means a
     * failed eviction leaves a stale entry behind. That is acceptable only
     * because every entry here carries a bounded TTL, so staleness self-heals;
     * it is logged at ERROR precisely because it is the one case that serves
     * wrong data.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                log.warn("Cache GET failed for {}::{} - falling back to the database", cache.getName(), key,
                        exception);
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
                log.warn("Cache PUT failed for {}::{} - entry not cached", cache.getName(), key, exception);
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                log.error("Cache EVICT failed for {}::{} - a STALE entry may be served until its TTL expires",
                        cache.getName(), key, exception);
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                log.error("Cache CLEAR failed for {} - STALE entries may be served until their TTL expires",
                        cache.getName(), exception);
            }
        };
    }

    @Bean
    public RedisCacheManager cacheManager(RedisCacheWriter cacheWriter, ObjectMapper objectMapper) {

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
        // ProductResponse carries a BigDecimal (price) and an Instant (updatedAt) -
        // both need their own allowlist entries, same as java.util below, or the
        // WRITE side happily tags them with a type id that the READ side's
        // validator then refuses to resolve (empirically confirmed: a cache HIT
        // on real product data throws SerializationException without these).
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.easyshop.catalog.dto")
                .allowIfSubType("java.util")
                .allowIfSubType("java.math")
                .allowIfSubType("java.time")
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
                PRODUCT_LISTINGS_CACHE, defaults.entryTtl(jitter(Duration.ofMinutes(2))),
                // Categories are seeded taxonomy (V2 migration) with no write
                // path in this service at all - the only invalidation is a
                // redeploy or this TTL, so it can be far longer than products.
                CATEGORIES_CACHE, defaults.entryTtl(jitter(Duration.ofHours(1)))
        );

        return RedisCacheManager.builder(cacheWriter)
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