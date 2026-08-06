package com.easyshop.gateway.session;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * These are all "refuse to start" assertions. A gateway that boots with a
 * missing or malformed encryption key and quietly writes plaintext refresh
 * tokens to disk looks completely healthy from the outside, which is exactly why
 * the failure has to happen loudly at startup instead.
 */
class GatewaySessionPropertiesTest {

    private static final String VALID_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void aValidKeyIsAccepted() {
        GatewaySessionProperties properties =
                new GatewaySessionProperties(VALID_KEY, Duration.ofMinutes(30));

        assertThat(properties.decodedEncryptionKey()).hasSize(32);
        assertThat(properties.authorizedClientTtl()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void aMissingKeyStopsStartup() {
        assertThatThrownBy(() -> new GatewaySessionProperties(null, Duration.ofMinutes(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token-encryption-key is required");

        assertThatThrownBy(() -> new GatewaySessionProperties("  ", Duration.ofMinutes(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aKeyOfTheWrongLengthStopsStartup() {
        // A 16-byte key would still "work" as AES-128 - silently weaker than the
        // AES-256 the configuration claims. Rejected rather than downgraded.
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new GatewaySessionProperties(shortKey, Duration.ofMinutes(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void aNonBase64KeyStopsStartup() {
        assertThatThrownBy(() -> new GatewaySessionProperties("not base64 @@@", Duration.ofMinutes(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Base64");
    }

    @Test
    void aNonPositiveTtlStopsStartup() {
        // Zero would mean EXPIRE deletes the entry immediately, so every relayed
        // call would fail with no obvious cause.
        assertThatThrownBy(() -> new GatewaySessionProperties(VALID_KEY, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authorized-client-ttl");
    }
}
