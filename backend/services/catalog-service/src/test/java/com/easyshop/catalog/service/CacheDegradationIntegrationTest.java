package com.easyshop.catalog.service;

import com.easyshop.catalog.dto.ProductDtos.ProductResponse;
import com.easyshop.catalog.dto.ProductDtos.UpdateProductRequest;
import com.easyshop.catalog.entity.Product;
import com.easyshop.catalog.repository.CategoryRepository;
import com.easyshop.catalog.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A cache is an optimisation, and losing it must not take the service with it.
 * Without the CacheErrorHandler in CacheConfig this whole class fails: every
 * @Cacheable read throws RedisConnectionFailureException straight through the
 * service and the product API answers 500 for as long as Redis is unreachable -
 * turning a Redis blip into a full catalog outage.
 *
 * This test kills Redis for real rather than mocking a failure, because the
 * exception type and the layer it surfaces from are precisely what is being
 * asserted.
 *
 * Its own container and @DirtiesContext: the container is stopped mid-test and
 * must not leak into any other class's context.
 */
@SpringBootTest(
        classes = ProductCachingIntegrationTest.CacheTestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false",
                "spring.data.redis.password=",
                "MYSQL_PASSWORD=unused"
        })
@DirtiesContext
@Testcontainers
class CacheDegradationIntegrationTest {

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

    @MockitoBean
    private ProductRepository productRepository;

    @MockitoBean
    private CategoryRepository categoryRepository;

    @Test
    void readsAndWritesKeepWorkingWhenRedisGoesDown() {
        UUID productId = UUID.randomUUID();
        Product product = Product.createNew("SKU-1", "Mechanical Keyboard", "Tactile, 87 keys",
                new BigDecimal("129.99"), UUID.randomUUID());
        setId(product, productId);
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));

        // Warm the cache while Redis is healthy, so the next read would be a hit.
        assertThat(productService.getProduct(productId).name()).isEqualTo("Mechanical Keyboard");
        productService.getProduct(productId);
        verify(productRepository, times(1)).findById(productId);

        REDIS.stop();

        // The read degrades to a database round trip instead of blowing up. This
        // is the sync=true path, which routes through the error handler and then
        // falls back to the value loader.
        ProductResponse whileDown = productService.getProduct(productId);
        assertThat(whileDown.name()).isEqualTo("Mechanical Keyboard");
        assertThat(whileDown.price()).isEqualByComparingTo("129.99");

        // And the write path survives its failed eviction rather than rolling
        // back a committed database change over a cache problem.
        assertThatCode(() -> productService.updateProduct(productId, new UpdateProductRequest(
                "Mechanical Keyboard v2", "desc", new BigDecimal("139.99"))))
                .doesNotThrowAnyException();

        assertThat(productService.getProduct(productId).name()).isEqualTo("Mechanical Keyboard v2");
    }

    private static void setId(Product product, UUID id) {
        try {
            var field = Product.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(product, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
