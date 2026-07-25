package com.easyshop.payment.saga;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * SHA-256 fingerprints for the payment-side idempotency keys. IdempotencyStore's
 * API requires one; in practice a commandId collision carrying different data
 * should never happen (commandId is generated once per outbox event, not
 * client-suppliable), so this is cheap, honest protection against a
 * hypothetical bug elsewhere - not a value ever expected to actually mismatch.
 */
final class PaymentFingerprint {

    private PaymentFingerprint() {}

    static String charge(UUID orderId, BigDecimal amount, String currency) {
        return hash(orderId.toString(), amount.toPlainString(), currency);
    }

    static String refund(UUID orderId, UUID originalTransactionId) {
        return hash(orderId.toString(), originalTransactionId.toString());
    }

    private static String hash(String... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                md.update(part.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
