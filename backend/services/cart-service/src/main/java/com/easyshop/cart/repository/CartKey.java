package com.easyshop.cart.repository;

import java.util.UUID;

/**
 * The two cart key-spaces, modelled as a type rather than a string convention.
 *
 * WHY A SEALED TYPE AND NOT A STRING: the TTL a cart gets is a function of
 * which key-space it lives in, and those two facts must never drift apart. A
 * sealed interface makes {@code CartProperties.ttlFor} an EXHAUSTIVE switch -
 * add a third cart flavour (a saved-for-later list, a B2B quote) and the
 * compiler refuses to build until someone has decided what its TTL is. The
 * alternative - passing raw key strings around and looking the TTL up by
 * prefix - fails silently by falling through to a default, which is exactly
 * how a cart ends up with the wrong lifetime.
 *
 * NO COLLISION BETWEEN THE TWO SPACES, structurally: a session key is
 * {@code cart:{uuid}} and a UUID cannot contain a colon, so no guest key
 * {@code cart:guest:{token}} can ever be mistaken for one. Asserted in
 * CartTtlIntegrationTest rather than left as a comment nobody re-checks.
 */
public sealed interface CartKey {

    /** The literal Redis key. The ONLY place cart key strings are constructed. */
    String redisKey();

    // Returning the precise subtype, not CartKey: mergeGuestIntoSession takes a
    // Guest and a Session specifically, and callers should reach that without
    // a downcast.
    static Session session(UUID userId) {
        return new Session(userId);
    }

    static Guest guest(String token) {
        return new Guest(token);
    }

    /**
     * An authenticated user's cart. Identity comes from the JWT sub claim, never
     * from client input - see CartController's note on why the cart has no
     * addressable id and is therefore IDOR-proof by construction.
     */
    record Session(UUID userId) implements CartKey {

        public Session {
            if (userId == null) {
                throw new IllegalArgumentException("userId is required for a session cart");
            }
        }

        @Override
        public String redisKey() {
            return "cart:" + userId;
        }
    }

    /**
     * An anonymous shopper's cart, addressed by a token the client holds.
     *
     * The token IS the only credential for this cart, so it must be a
     * SERVER-MINTED, high-entropy, opaque value (a UUID is the obvious choice) -
     * never anything a caller can choose. The guard below rejects blank tokens
     * and tokens containing a colon: without it, a client-supplied token could
     * inject extra key structure and address a key outside its own space. That
     * guard belongs here, at the point the key is built, rather than in the
     * HTTP layer that will land with the guest endpoints - a validation the
     * follow-up ticket cannot forget to write because it already exists.
     */
    record Guest(String token) implements CartKey {

        private static final int MAX_TOKEN_LENGTH = 64;

        public Guest {
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("guest cart token is required");
            }
            if (token.indexOf(':') >= 0) {
                throw new IllegalArgumentException("guest cart token must not contain ':'");
            }
            if (token.length() > MAX_TOKEN_LENGTH) {
                throw new IllegalArgumentException(
                        "guest cart token must be at most " + MAX_TOKEN_LENGTH + " characters");
            }
        }

        @Override
        public String redisKey() {
            return "cart:guest:" + token;
        }
    }
}
