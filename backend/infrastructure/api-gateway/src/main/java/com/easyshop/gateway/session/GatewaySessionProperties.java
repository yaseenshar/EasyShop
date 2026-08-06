package com.easyshop.gateway.session;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Base64;

/**
 * Settings for the Redis-backed BFF session store.
 *
 * NOTE the session's own idle timeout is NOT here - that is Spring Session's
 * standard {@code spring.session.timeout}, and inventing a parallel knob for it
 * would mean two properties that must agree and eventually will not. Only the
 * things Spring has no property for live here.
 *
 * THE ENCRYPTION KEY IS MANDATORY, and the gateway refuses to start without a
 * valid one. That is deliberate: the alternative - quietly falling back to
 * plaintext when the key is absent - would mean a deployment that forgot to set
 * the variable silently writes every user's refresh token to disk in the clear,
 * and nothing about the running system would look wrong. A refresh token mints
 * new access tokens on demand, so it is a far more valuable secret than anything
 * else this Redis holds, and every service in the compose file shares one Redis
 * password - encrypting here is what stops "can read Redis" from meaning "can
 * impersonate every logged-in user".
 */
@ConfigurationProperties(prefix = "easyshop.gateway.session")
public record GatewaySessionProperties(

        /**
         * Base64-encoded AES key, 32 bytes (AES-256) once decoded. Generate with:
         * {@code openssl rand -base64 32}
         *
         * Rotating it invalidates every stored authorized client - users are
         * asked to sign in again, which is the same failure mode as a gateway
         * restart used to be, and no worse.
         */
        String tokenEncryptionKey,

        /**
         * How long a stored authorized client (access + refresh token) lives in
         * Redis. Defaults to 30 minutes to line up with Spring Session's own
         * default idle timeout: a token set that outlives the session it belongs
         * to is unreachable anyway, and leaving it there just keeps a decryptable
         * credential on disk for longer than anything can use it.
         */
        @DefaultValue("30m") Duration authorizedClientTtl) {

    private static final int AES_256_KEY_BYTES = 32;

    public GatewaySessionProperties {
        if (tokenEncryptionKey == null || tokenEncryptionKey.isBlank()) {
            throw new IllegalArgumentException(
                    "easyshop.gateway.session.token-encryption-key is required - generate one with "
                            + "'openssl rand -base64 32'. Refusing to start rather than store refresh "
                            + "tokens unencrypted.");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(tokenEncryptionKey.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "easyshop.gateway.session.token-encryption-key must be valid Base64", e);
        }
        if (decoded.length != AES_256_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "easyshop.gateway.session.token-encryption-key must decode to " + AES_256_KEY_BYTES
                            + " bytes (AES-256), but was " + decoded.length);
        }
        if (authorizedClientTtl == null || authorizedClientTtl.isZero() || authorizedClientTtl.isNegative()) {
            throw new IllegalArgumentException(
                    "easyshop.gateway.session.authorized-client-ttl must be positive, but was "
                            + authorizedClientTtl);
        }
    }

    public byte[] decodedEncryptionKey() {
        return Base64.getDecoder().decode(tokenEncryptionKey.trim());
    }
}
