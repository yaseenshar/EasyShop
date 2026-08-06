package com.easyshop.gateway.session;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Authenticated encryption for OAuth2 token values before they touch Redis.
 *
 * AES-GCM, not AES-CBC. GCM authenticates the ciphertext, so a token tampered
 * with in Redis fails to decrypt instead of decrypting into something else.
 * With an unauthenticated mode, anyone able to write to Redis could flip bits
 * in a stored token and the gateway would hand the result downstream as if the
 * user had presented it.
 *
 * A FRESH RANDOM IV PER ENCRYPTION, prepended to the output. Reusing an IV
 * under one key is the classic way to break GCM outright - it leaks the
 * authentication subkey and, with it, the ability to forge. The IV is not
 * secret, only unique, so shipping it alongside the ciphertext is both normal
 * and necessary: decryption needs it.
 *
 * Encrypting the same token twice therefore yields different ciphertext. That is
 * correct and is what the tests assert - identical output would mean a fixed IV.
 */
public class TokenCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;          // 96 bits, the size GCM is specified for
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public TokenCipher(byte[] keyBytes) {
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    /** Returns Base64(iv || ciphertext||tag), or null for a null input. */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array());
        } catch (Exception e) {
            // Never let the plaintext reach the message - this is a token.
            throw new IllegalStateException("Failed to encrypt token value", e);
        }
    }

    /** Inverse of {@link #encrypt}; null in, null out. */
    public String decrypt(String encoded) {
        if (encoded == null) {
            return null;
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(encoded));
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt token value", e);
        }
    }
}
