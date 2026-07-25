package com.easyshop.payment.gateway;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentGateway {

    /**
     * @param pspIdempotencyKey layer 3 of the charge's three-layer defense
     *                          (Redis lock + DB UNIQUE + this): the PSP's OWN
     *                          dedupe, so a redelivery that races a crash
     *                          between "PSP charged" and "we recorded it"
     *                          gets the ORIGINAL result back from the PSP
     *                          instead of a second charge. See
     *                          SagaIdempotencyKeys.pspKey - deterministic per
     *                          commandId, stable across redeliveries.
     */
    GatewayResult charge(UUID orderId, BigDecimal amount, String currency, String pspIdempotencyKey);

    /**
     * @param pspIdempotencyKey SagaIdempotencyKeys.refundPspKey - a DIFFERENT
     *                          value from the charge's key for the same
     *                          commandId, so a refund and a charge never
     *                          accidentally dedupe against each other at the
     *                          PSP.
     */
    GatewayResult refund(UUID originalTransactionId, String pspIdempotencyKey);

    record GatewayResult(boolean success, String gatewayReference, String failureReason) {
        public static GatewayResult success(String gatewayReference) {
            return new GatewayResult(true, gatewayReference, null);
        }
        public static GatewayResult declined(String reason) {
            return new GatewayResult(false, null, reason);
        }
    }
}