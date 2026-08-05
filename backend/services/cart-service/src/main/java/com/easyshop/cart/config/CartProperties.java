package com.easyshop.cart.config;

import com.easyshop.cart.repository.CartKey;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Cart expiry policy - the eviction strategy for cart-service, expressed as
 * configuration rather than as a constant recompiled into the jar.
 *
 * WHY TWO DIFFERENT TTLs. Guest and session carts are not the same asset:
 *
 *   SESSION (30d) - the shopper is authenticated, so the cart is reachable
 *   from any device they log in on and they reasonably expect it to still be
 *   there next month. It is also cheap to keep: bounded by the number of real
 *   accounts.
 *
 *   GUEST (7d) - addressed only by a token held client-side. Its realistic
 *   lifetime is already bounded by that token surviving in the browser, so a
 *   30-day server-side key mostly outlives the only thing that could reach it,
 *   and every one of those orphans still costs memory. Guest keys are also the
 *   unbounded population - one per anonymous browsing session, bots included -
 *   which makes them the obvious lever when Redis memory gets tight. Shorter is
 *   both cheaper and closer to the truth about how long the cart is reachable.
 *
 * Both are expressed as Durations, so application.yml can say plain {@code 30d}
 * or {@code 12h} and an ops change no longer needs a rebuild. Values are
 * validated at startup: a nonsensical TTL should stop the service booting, not
 * quietly become the reason carts vanish.
 */
@ConfigurationProperties(prefix = "easyshop.cart")
public record CartProperties(

        @DefaultValue("30d") Duration sessionTtl,
        @DefaultValue("7d") Duration guestTtl) {

    public CartProperties {
        requirePositive("easyshop.cart.session-ttl", sessionTtl);
        requirePositive("easyshop.cart.guest-ttl", guestTtl);
    }

    /**
     * The single place a cart's lifetime is decided.
     *
     * Exhaustive over CartKey's permitted subtypes with no default branch - that
     * is deliberate. A new cart flavour added to CartKey breaks THIS switch at
     * compile time, forcing an explicit TTL decision instead of silently
     * inheriting whatever a default arm happened to return.
     */
    public Duration ttlFor(CartKey key) {
        return switch (key) {
            case CartKey.Session ignored -> sessionTtl;
            case CartKey.Guest ignored -> guestTtl;
        };
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be a positive duration, but was " + value);
        }
    }
}
