package com.easyshop.cart.config;

import com.easyshop.cart.repository.CartKey;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The expiry POLICY, tested without Redis - these are decisions about which
 * cart gets which lifetime, and none of them need a server to verify.
 * CartTtlIntegrationTest covers the other half: that Redis actually applies
 * what this class decides.
 */
class CartPropertiesTest {

    @Test
    void guestCartsExpireSoonerThanSessionCarts() {
        CartProperties properties = new CartProperties(Duration.ofDays(30), Duration.ofDays(7));

        Duration session = properties.ttlFor(CartKey.session(UUID.randomUUID()));
        Duration guest = properties.ttlFor(CartKey.guest("tok-1"));

        // The whole point of the ticket: two key-spaces, two lifetimes. If these
        // ever come out equal, the guest/session distinction has quietly
        // collapsed and the shorter guest expiry is no longer being applied.
        assertThat(session).isEqualTo(Duration.ofDays(30));
        assertThat(guest).isEqualTo(Duration.ofDays(7));
        assertThat(guest).isLessThan(session);
    }

    @Test
    void ttlsMustBePositive() {
        // A zero or negative TTL passed to Redis EXPIRE deletes the key on the
        // spot, so this misconfiguration would present as "carts vanish
        // instantly" rather than as a config error. Fail at startup instead.
        assertThatThrownBy(() -> new CartProperties(Duration.ZERO, Duration.ofDays(7)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("session-ttl");

        assertThatThrownBy(() -> new CartProperties(Duration.ofDays(30), Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("guest-ttl");

        assertThatThrownBy(() -> new CartProperties(null, Duration.ofDays(7)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void guestTokensCannotInjectExtraKeyStructure() {
        // A token carrying a colon could otherwise address a key outside the
        // guest space. Rejected where the key is built, so the guest endpoints
        // landing later inherit the guard for free.
        assertThatThrownBy(() -> CartKey.guest("abc:def"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("':'");

        assertThatThrownBy(() -> CartKey.guest("  "))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> CartKey.guest("x".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theTwoKeySpacesCannotCollide() {
        UUID id = UUID.randomUUID();

        // A session key is cart:{uuid} and a UUID cannot contain a colon, so no
        // guest key can ever be mistaken for a session key - even when the guest
        // token is literally some user's id.
        assertThat(CartKey.session(id).redisKey()).isEqualTo("cart:" + id);
        assertThat(CartKey.guest(id.toString()).redisKey()).isEqualTo("cart:guest:" + id);
        assertThat(CartKey.session(id).redisKey()).isNotEqualTo(CartKey.guest(id.toString()).redisKey());
    }
}
