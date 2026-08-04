package com.easyshop.catalog.service;

import com.easyshop.catalog.config.CacheConfig;
import com.easyshop.catalog.dto.ProductDtos.PagedResponse;
import com.easyshop.catalog.dto.ProductDtos.ProductResponse;
import com.easyshop.catalog.dto.ProductDtos.UpdateProductRequest;
import com.easyshop.catalog.entity.Category;
import com.easyshop.catalog.entity.Product;
import com.easyshop.catalog.repository.CategoryRepository;
import com.easyshop.catalog.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.transaction.TransactionAwareCacheDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.cache.CacheStatistics;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises the cache-aside layer against a REAL Redis, because the failure
 * modes that matter here are all serialization failures that no mocked
 * CacheManager can reproduce: the write side happily accepts values that the
 * read side then refuses to resolve. Every assertion below that reads a value
 * back out is really asserting "the polymorphic type configuration in
 * CacheConfig round-trips this shape".
 *
 * The repository is mocked so these tests stay about caching and don't drag in
 * MySQL/Flyway/JPA. The transaction manager is a real (if no-op) one, because
 * the cache manager is transactionAware() - evictions are deferred to commit,
 * so without transaction synchronization actually running, the eviction tests
 * would pass for the wrong reason.
 */
@SpringBootTest(
        classes = ProductCachingIntegrationTest.CacheTestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false",
                // application.yml (on the test classpath) expects these from the
                // environment; nothing here touches MySQL, and the test Redis is
                // unauthenticated.
                "spring.data.redis.password=",
                "MYSQL_PASSWORD=unused"
        })
@Testcontainers
class ProductCachingIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private ProductService productService;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private ProductRepository productRepository;

    @MockitoBean
    private CategoryRepository categoryRepository;

    private UUID productId;
    private Product product;

    @BeforeEach
    void setUp() {
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
        reset(productRepository, categoryRepository);

        product = Product.createNew("SKU-1", "Mechanical Keyboard", "Tactile, 87 keys",
                new BigDecimal("129.99"), UUID.randomUUID());
        productId = UUID.randomUUID();
        setField(product, "id", productId);
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
    }

    @Test
    void secondGetIsServedFromCacheAndDeserializesToTheSameDto() {
        ProductResponse first = productService.getProduct(productId);
        ProductResponse second = productService.getProduct(productId);

        // The DB was hit once; the second call came back through Redis.
        verify(productRepository, times(1)).findById(productId);

        // Equality here is the real assertion: a broken type configuration
        // either throws on read or hands back a LinkedHashMap. Record equals()
        // also proves BigDecimal/Instant/UUID all survived the round trip with
        // their types intact - the exact fields CacheConfig allowlists.
        assertThat(second).isEqualTo(first);
        assertThat(second.price()).isEqualByComparingTo("129.99");
        assertThat(second.updatedAt()).isEqualTo(product.getUpdatedAt());
    }

    @Test
    void cacheKeysAreHumanReadableStrings() {
        productService.getProduct(productId);

        assertThat(redisTemplate.keys(CacheConfig.PRODUCTS_CACHE + "::*"))
                .containsExactly(CacheConfig.PRODUCTS_CACHE + "::" + productId);
    }

    @Test
    void updateEvictsTheProductEntry() {
        productService.getProduct(productId);
        productService.updateProduct(productId, new UpdateProductRequest(
                "Mechanical Keyboard v2", "Tactile, 87 keys, hot-swap", new BigDecimal("139.99")));

        ProductResponse afterUpdate = productService.getProduct(productId);

        // 1 initial load + 1 update + 1 reload = the reload proves the entry
        // was evicted rather than serving the pre-update name.
        verify(productRepository, times(3)).findById(productId);
        assertThat(afterUpdate.name()).isEqualTo("Mechanical Keyboard v2");
        assertThat(afterUpdate.price()).isEqualByComparingTo("139.99");
    }

    @Test
    void deactivateEvictsTheProductEntry() {
        productService.getProduct(productId);
        productService.deactivateProduct(productId);

        assertThat(productService.getProduct(productId).active()).isFalse();
        verify(productRepository, times(3)).findById(productId);
    }

    @Test
    void pagedListingRoundTripsThroughRedis() {
        UUID categoryId = UUID.randomUUID();
        when(productRepository.findByCategoryIdAndActiveTrue(eq(categoryId), any(Pageable.class)))
                .thenAnswer(invocation -> page(invocation.getArgument(1)));

        PagedResponse<ProductResponse> first = productService.listByCategory(categoryId, 0, 20, false);
        PagedResponse<ProductResponse> second = productService.listByCategory(categoryId, 0, 20, false);

        verify(productRepository, times(1))
                .findByCategoryIdAndActiveTrue(eq(categoryId), any(Pageable.class));
        // PagedResponse is the shape most likely to come back as a raw
        // LinkedHashMap - a generic record wrapping a List of records.
        assertThat(second).isEqualTo(first);
        assertThat(second.content()).singleElement().isEqualTo(first.content().get(0));
        assertThat(second.totalElements()).isEqualTo(1);
    }

    @Test
    void deepPagesAreNotCached() {
        UUID categoryId = UUID.randomUUID();
        when(productRepository.findByCategoryIdAndActiveTrue(eq(categoryId), any(Pageable.class)))
                .thenAnswer(invocation -> page(invocation.getArgument(1)));

        productService.listByCategory(categoryId, 4, 20, false);
        productService.listByCategory(categoryId, 4, 20, false);

        verify(productRepository, times(2))
                .findByCategoryIdAndActiveTrue(eq(categoryId), any(Pageable.class));
        assertThat(redisTemplate.keys(CacheConfig.PRODUCT_LISTINGS_CACHE + "::*")).isEmpty();
    }

    @Test
    void adminListingsIncludingInactiveAreNotCached() {
        UUID categoryId = UUID.randomUUID();
        when(productRepository.findByCategoryId(eq(categoryId), any(Pageable.class)))
                .thenAnswer(invocation -> page(invocation.getArgument(1)));

        productService.listByCategory(categoryId, 0, 20, true);
        productService.listByCategory(categoryId, 0, 20, true);

        verify(productRepository, times(2)).findByCategoryId(eq(categoryId), any(Pageable.class));
        assertThat(redisTemplate.keys(CacheConfig.PRODUCT_LISTINGS_CACHE + "::*")).isEmpty();
    }

    @Test
    void updateClearsTheWholeListingsCache() {
        UUID categoryId = UUID.randomUUID();
        when(productRepository.findByCategoryIdAndActiveTrue(eq(categoryId), any(Pageable.class)))
                .thenAnswer(invocation -> page(invocation.getArgument(1)));
        productService.listByCategory(categoryId, 0, 20, false);
        assertThat(redisTemplate.keys(CacheConfig.PRODUCT_LISTINGS_CACHE + "::*")).isNotEmpty();

        productService.updateProduct(productId, new UpdateProductRequest(
                "Mechanical Keyboard v2", "desc", new BigDecimal("139.99")));

        assertThat(redisTemplate.keys(CacheConfig.PRODUCT_LISTINGS_CACHE + "::*")).isEmpty();
    }

    /**
     * Regression test for the async-write default in Spring Data Redis 4.x.
     *
     * Cache writes are fire-and-forget whenever the connection factory is
     * reactive-capable (Lettuce is), so before CacheConfig enabled
     * immediateWrites() this assertion FAILED: the evicted keys were still in
     * Redis when the @Transactional write method returned, and only vanished
     * a few milliseconds later. In production that means a caller could get a
     * 200 from PUT /products/{id} and then read back the old price.
     *
     * There is deliberately no sleep, retry or awaitility here - the entire
     * point is that eviction has already happened by the time control returns.
     */
    @Test
    void evictionIsVisibleImmediatelyWhenTheWriteMethodReturns() {
        UUID categoryId = UUID.randomUUID();
        when(productRepository.findByCategoryIdAndActiveTrue(eq(categoryId), any(Pageable.class)))
                .thenAnswer(invocation -> page(invocation.getArgument(1)));
        productService.getProduct(productId);
        productService.listByCategory(categoryId, 0, 20, false);
        assertThat(redisTemplate.keys("*")).hasSize(2);

        productService.updateProduct(productId, new UpdateProductRequest(
                "Mechanical Keyboard v2", "desc", new BigDecimal("139.99")));

        assertThat(redisTemplate.keys(CacheConfig.PRODUCTS_CACHE + "::*")).isEmpty();
        assertThat(redisTemplate.keys(CacheConfig.PRODUCT_LISTINGS_CACHE + "::*")).isEmpty();
    }

    /**
     * Guards the collectStatistics() call in CacheConfig. Statistics are OFF by
     * default, and with them off the cache_gets{result="hit"} meters that
     * /actuator/prometheus publishes sit at zero forever - a cache that never
     * hits and a cache that is never measured look identical on the dashboard.
     */
    @Test
    void hitsAndMissesAreRecordedForMetrics() {
        var redisCache = (RedisCache) ((TransactionAwareCacheDecorator)
                cacheManager.getCache(CacheConfig.PRODUCTS_CACHE)).getTargetCache();
        // Counters live on the shared cache writer and accumulate across tests,
        // so this asserts the delta rather than an absolute count.
        CacheStatistics before = redisCache.getStatistics();

        productService.getProduct(productId);
        productService.getProduct(productId);

        CacheStatistics after = redisCache.getStatistics();
        assertThat(after.getMisses() - before.getMisses()).isEqualTo(1);
        assertThat(after.getHits() - before.getHits()).isEqualTo(1);
        assertThat(after.getPuts() - before.getPuts()).isEqualTo(1);
    }

    @Test
    void categoryListingIsCachedUnderASingleKey() {
        when(categoryRepository.findAll()).thenReturn(List.of(category("Peripherals", "peripherals")));

        var first = categoryService.listCategories();
        var second = categoryService.listCategories();

        verify(categoryRepository, times(1)).findAll();
        assertThat(second).isEqualTo(first);
        assertThat(second.categories()).singleElement()
                .satisfies(it -> assertThat(it.slug()).isEqualTo("peripherals"));
        assertThat(redisTemplate.keys(CacheConfig.CATEGORIES_CACHE + "::*"))
                .containsExactly(CacheConfig.CATEGORIES_CACHE + "::all");
    }

    private static Category category(String name, String slug) {
        Category category = Category.createNew(name, slug);
        setField(category, "id", UUID.randomUUID());
        return category;
    }

    private Page<Product> page(Pageable pageable) {
        return new PageImpl<>(List.of(product), PageRequest.of(pageable.getPageNumber(),
                pageable.getPageSize()), 1);
    }

    /** Ids are DB-generated; tests need stable ones to key the cache on. */
    private static void setField(Object target, String name, Object value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Configuration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class
    })
    @Import({CacheConfig.class, ProductService.class, CategoryService.class})
    static class CacheTestApp {

        /**
         * Drives TransactionSynchronizationManager for real - registering
         * synchronizations and firing afterCommit - without needing a database.
         * That is precisely the machinery transactionAware() eviction hangs off.
         */
        @Bean
        PlatformTransactionManager transactionManager() {
            return new AbstractPlatformTransactionManager() {
                @Override
                protected Object doGetTransaction() {
                    return new Object();
                }

                @Override
                protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition d) {
                }

                @Override
                protected void doCommit(DefaultTransactionStatus status) {
                }

                @Override
                protected void doRollback(DefaultTransactionStatus status) {
                }
            };
        }
    }
}
