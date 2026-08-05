package com.easyshop.cart.repository;

import com.easyshop.cart.CartTestApp;
import com.easyshop.cart.config.CartProperties;
import com.easyshop.cart.dto.CartDtos.CartItem;
import com.easyshop.cart.service.CartService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cart expiry against a REAL Redis, because every property under test is a
 * server behaviour rather than a client one: what EXPIRE does to an existing
 * key, that HDEL of the final field removes the hash entirely, and that TTL
 * reports what we think it does. A mocked RedisTemplate would assert only that
 * we called the methods we already know we call.
 *
 * The TTLs here are deliberately NOT the application.yml defaults - they are
 * overridden below to 45d/3h. That is the actual subject of the ticket: the
 * lifetimes are configuration, so the test proves a deployment can change them
 * rather than proving a constant still equals itself.
 */
@SpringBootTest(
        classes = CartTestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false",
                "spring.data.redis.password=",
                "easyshop.cart.session-ttl=45d",
                "easyshop.cart.guest-ttl=3h"
        })
@Testcontainers
class CartTtlIntegrationTest {

    private static final Duration CONFIGURED_SESSION_TTL = Duration.ofDays(45);
    private static final Duration CONFIGURED_GUEST_TTL = Duration.ofHours(3);

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private CartService cartService;

    @Autowired
    private CartProperties cartProperties;

    @Autowired
    private StringRedisTemplate redis;

    private CartKey sessionCart;
    private CartKey guestCart;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        sessionCart = CartKey.session(UUID.randomUUID());
        guestCart = CartKey.guest(UUID.randomUUID().toString());
    }

    @Test
    void configuredTtlsAreBoundFromProperties() {
        // Proves the binding end to end - 45d/3h are not the defaults baked
        // into CartProperties, so seeing them here means yaml actually drives
        // the expiry policy.
        assertThat(cartProperties.sessionTtl()).isEqualTo(CONFIGURED_SESSION_TTL);
        assertThat(cartProperties.guestTtl()).isEqualTo(CONFIGURED_GUEST_TTL);
    }

    @Test
    void sessionCartExpiresOnTheSessionSchedule() {
        cartRepository.put(sessionCart, item());

        assertThat(cartRepository.timeToLive(sessionCart)).isNotNull().isCloseTo(
                CONFIGURED_SESSION_TTL, Duration.ofSeconds(10));
    }

    @Test
    void guestCartExpiresOnTheShorterGuestSchedule() {
        cartRepository.put(guestCart, item());

        assertThat(cartRepository.timeToLive(guestCart)).isNotNull().isCloseTo(
                CONFIGURED_GUEST_TTL, Duration.ofSeconds(10));
    }

    /**
     * The regression that matters most: both key-spaces getting the SAME expiry
     * is exactly what this ticket exists to prevent, and it is the failure mode
     * a single shared constant produces. Asserting the gap directly means a
     * future refactor that collapses the two back into one TTL fails here.
     */
    @Test
    void theTwoKeySpacesGetDifferentLifetimes() {
        cartRepository.put(sessionCart, item());
        cartRepository.put(guestCart, item());

        assertThat(cartRepository.timeToLive(guestCart))
                .isLessThan(cartRepository.timeToLive(sessionCart));
    }

    /**
     * Sliding expiry, verified without waiting 45 days: age the key artificially
     * by stamping a 5-second TTL on it, then mutate. A re-armed cart jumps back
     * to the full window; a cart with a fixed (non-sliding) expiry would still
     * be sitting near 5 seconds.
     */
    @Test
    void everyMutationReArmsTheExpiry() {
        cartRepository.put(sessionCart, item());
        redis.expire(sessionCart.redisKey(), Duration.ofSeconds(5));
        assertThat(cartRepository.timeToLive(sessionCart)).isLessThan(Duration.ofMinutes(1));

        cartRepository.put(sessionCart, item());

        assertThat(cartRepository.timeToLive(sessionCart)).isCloseTo(
                CONFIGURED_SESSION_TTL, Duration.ofSeconds(10));
    }

    @Test
    void removingAnItemAlsoReArmsTheExpiry() {
        CartItem first = item();
        CartItem second = item();
        cartRepository.put(sessionCart, first);
        cartRepository.put(sessionCart, second);
        redis.expire(sessionCart.redisKey(), Duration.ofSeconds(5));

        cartRepository.remove(sessionCart, first.productId());

        assertThat(cartRepository.timeToLive(sessionCart)).isCloseTo(
                CONFIGURED_SESSION_TTL, Duration.ofSeconds(10));
    }

    /**
     * Documents the deliberate asymmetry in CartRepository: "abandoned" means
     * not-modified, so viewing a cart must not extend its life. Without this
     * test, someone adding a touch() to findAll() as an "improvement" would
     * silently make every cart with an open browser tab immortal.
     */
    @Test
    void readingACartDoesNotExtendItsLife() {
        cartRepository.put(sessionCart, item());
        redis.expire(sessionCart.redisKey(), Duration.ofSeconds(30));

        cartRepository.findAll(sessionCart);
        cartService.getCart(sessionCart);

        assertThat(cartRepository.timeToLive(sessionCart)).isLessThanOrEqualTo(Duration.ofSeconds(30));
    }

    /**
     * Redis deletes a hash when its last field goes, so an emptied cart leaves
     * no key behind - nothing to expire, and no zero-item husk occupying memory
     * for the next 45 days. Worth pinning because the repository calls expire()
     * immediately after the delete, and that call must not resurrect the key.
     */
    @Test
    void emptyingACartLeavesNoKeyBehind() {
        CartItem only = item();
        cartRepository.put(sessionCart, only);

        cartRepository.remove(sessionCart, only.productId());

        assertThat(redis.hasKey(sessionCart.redisKey())).isFalse();
        assertThat(cartRepository.timeToLive(sessionCart)).isNull();
    }

    @Test
    void clearingACartDropsTheKeyOutright() {
        cartRepository.put(sessionCart, item());

        cartService.clearCart(sessionCart);

        assertThat(redis.hasKey(sessionCart.redisKey())).isFalse();
    }

    /**
     * The same UUID used as a user id and as a guest token must address two
     * independent carts. This is the structural no-collision claim in CartKey,
     * checked against a real server rather than by reading the string
     * concatenation and agreeing with it.
     */
    @Test
    void guestAndSessionCartsWithTheSameIdentifierStaySeparate() {
        UUID shared = UUID.randomUUID();
        CartKey session = CartKey.session(shared);
        CartKey guest = CartKey.guest(shared.toString());

        cartRepository.put(session, item());

        assertThat(cartRepository.findAll(session)).hasSize(1);
        assertThat(cartRepository.findAll(guest)).isEmpty();
        assertThat(cartRepository.timeToLive(guest)).isNull();
    }

    private static CartItem item() {
        return new CartItem(UUID.randomUUID(), "Mechanical Keyboard",
                new BigDecimal("129.99"), 1, Instant.now());
    }

}
