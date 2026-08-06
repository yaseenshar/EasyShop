package com.easyshop.gateway.session;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenCipherTest {

    private static final String REFRESH_TOKEN = "eyJhbGciOiJIUzI1NiJ9.refresh-token-payload.signature";

    private final TokenCipher cipher = new TokenCipher(randomKey());

    @Test
    void encryptedTokensRoundTrip() {
        String encrypted = cipher.encrypt(REFRESH_TOKEN);

        assertThat(encrypted).isNotEqualTo(REFRESH_TOKEN);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(REFRESH_TOKEN);
    }

    @Test
    void theSameTokenEncryptsDifferentlyEveryTime() {
        String first = cipher.encrypt(REFRESH_TOKEN);
        String second = cipher.encrypt(REFRESH_TOKEN);

        // Identical ciphertext would mean a fixed IV, which under GCM leaks the
        // authentication subkey and lets an attacker forge tokens outright. This
        // is the test that catches someone "simplifying" the random IV away.
        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo(REFRESH_TOKEN);
        assertThat(cipher.decrypt(second)).isEqualTo(REFRESH_TOKEN);
    }

    @Test
    void tamperedCiphertextIsRejectedRatherThanDecodedIntoSomethingElse() {
        byte[] raw = Base64.getDecoder().decode(cipher.encrypt(REFRESH_TOKEN));
        raw[raw.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(raw);

        // The point of GCM over CBC: anyone able to write to Redis must not be
        // able to flip bits and have the gateway relay the result downstream.
        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aDifferentKeyCannotRead() {
        String encrypted = cipher.encrypt(REFRESH_TOKEN);

        assertThatThrownBy(() -> new TokenCipher(randomKey()).decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failuresNeverLeakThePlaintext() {
        assertThatThrownBy(() -> cipher.decrypt("not-valid-base64-@@@"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(REFRESH_TOKEN);
    }

    @Test
    void nullPassesThroughSoAnAbsentRefreshTokenStaysAbsent() {
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null)).isNull();
    }

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }
}
