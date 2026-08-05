package com.easyshop.cart.service;

import com.easyshop.cart.CartTestApp;
import com.easyshop.cart.dto.CartDtos.CartItem;
import com.easyshop.cart.dto.CartDtos.CartResponse;
import com.easyshop.cart.repository.CartKey;
import com.easyshop.cart.repository.CartRepository;
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
 * Merge-on-login against a real Redis.
 *
 * The merge is the one cart operation that reads one key and writes another,
 * so it is where the guest/session split is most likely to go wrong: items
 * silently dropped, quantities double-counted, or the guest key surviving and
 * being merged twice. Each of those has a test below.
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
class GuestCartMergeIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private CartService cartService;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private StringRedisTemplate redis;

    private CartKey.Session session;
    private CartKey.Guest guest;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        session = CartKey.session(UUID.randomUUID());
        guest = CartKey.guest(UUID.randomUUID().toString());
    }

    @Test
    void guestItemsMoveIntoAnEmptySessionCart() {
        UUID keyboard = UUID.randomUUID();
        cartRepository.put(guest, item(keyboard, 2));

        CartResponse merged = cartService.mergeGuestIntoSession(guest, session);

        assertThat(merged.items()).singleElement()
                .satisfies(it -> {
                    assertThat(it.productId()).isEqualTo(keyboard);
                    assertThat(it.quantity()).isEqualTo(2);
                });
    }

    @Test
    void quantitiesSumWhenBothCartsHoldTheSameProduct() {
        UUID keyboard = UUID.randomUUID();
        cartRepository.put(session, item(keyboard, 3));
        cartRepository.put(guest, item(keyboard, 2));

        CartResponse merged = cartService.mergeGuestIntoSession(guest, session);

        // The chosen merge policy: nothing the shopper added on either side is
        // discarded. 3 + 2, not 3 and not 2.
        assertThat(merged.items()).singleElement()
                .satisfies(it -> assertThat(it.quantity()).isEqualTo(5));
        assertThat(merged.totalItems()).isEqualTo(5);
    }

    @Test
    void summedQuantityIsCappedAtTheMaximum() {
        UUID keyboard = UUID.randomUUID();
        cartRepository.put(session, item(keyboard, 60));
        cartRepository.put(guest, item(keyboard, 60));

        CartResponse merged = cartService.mergeGuestIntoSession(guest, session);

        // 120 would exceed the @Max(99) bound the write endpoints enforce, which
        // would leave the cart in a state the API itself would reject.
        assertThat(merged.items()).singleElement()
                .satisfies(it -> assertThat(it.quantity()).isEqualTo(99));
    }

    @Test
    void productsOnlyInOneCartAllSurvive() {
        UUID onlyInSession = UUID.randomUUID();
        UUID onlyInGuest = UUID.randomUUID();
        cartRepository.put(session, item(onlyInSession, 1));
        cartRepository.put(guest, item(onlyInGuest, 4));

        CartResponse merged = cartService.mergeGuestIntoSession(guest, session);

        assertThat(merged.items()).hasSize(2)
                .extracting(CartItem::productId)
                .containsExactlyInAnyOrder(onlyInSession, onlyInGuest);
    }

    @Test
    void theGuestCartIsGoneAfterMerging() {
        cartRepository.put(guest, item(UUID.randomUUID(), 1));

        cartService.mergeGuestIntoSession(guest, session);

        assertThat(redis.hasKey(guest.redisKey())).isFalse();
        assertThat(cartRepository.findAll(guest)).isEmpty();
    }

    /**
     * The reason deleting the guest cart matters: a client that retries the
     * merge call - a double-click, a flaky network, a refresh - must not add the
     * guest quantities a second time. Deleting the source makes the retry a
     * no-op rather than relying on the client to call exactly once.
     */
    @Test
    void mergingAgainDoesNotDoubleTheQuantities() {
        UUID keyboard = UUID.randomUUID();
        cartRepository.put(session, item(keyboard, 3));
        cartRepository.put(guest, item(keyboard, 2));

        cartService.mergeGuestIntoSession(guest, session);
        CartResponse afterSecondMerge = cartService.mergeGuestIntoSession(guest, session);

        assertThat(afterSecondMerge.items()).singleElement()
                .satisfies(it -> assertThat(it.quantity()).isEqualTo(5));
    }

    @Test
    void mergingAnEmptyGuestCartLeavesTheSessionCartIntact() {
        UUID keyboard = UUID.randomUUID();
        cartRepository.put(session, item(keyboard, 3));

        CartResponse merged = cartService.mergeGuestIntoSession(guest, session);

        assertThat(merged.items()).singleElement()
                .satisfies(it -> assertThat(it.quantity()).isEqualTo(3));
    }

    /**
     * Items crossing into the session key-space must pick up the SESSION
     * lifetime - a merged cart that kept the guest's 3-hour expiry would quietly
     * delete a signed-in user's cart the same afternoon.
     */
    @Test
    void theMergedCartCarriesTheSessionTtl() {
        cartRepository.put(guest, item(UUID.randomUUID(), 1));

        cartService.mergeGuestIntoSession(guest, session);

        assertThat(cartRepository.timeToLive(session))
                .isNotNull()
                .isCloseTo(Duration.ofDays(45), Duration.ofSeconds(10));
    }

    private static CartItem item(UUID productId, int quantity) {
        return new CartItem(productId, "Mechanical Keyboard",
                new BigDecimal("129.99"), quantity, Instant.now());
    }
}
