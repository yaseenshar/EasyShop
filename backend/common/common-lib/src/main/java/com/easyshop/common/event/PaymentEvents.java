package com.easyshop.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Payment outcome events, published by payment-service on topic
 * "payment.charge.reply" (SagaTopics.PAYMENT_CHARGE_REPLY in order-service) -
 * order-service's saga orchestrator listens for these to advance or
 * compensate the saga. Two distinct record shapes on one topic, same
 * reasoning as OrderEvents' Completed/Cancelled split: a single record with
 * a boolean success flag forces every field to be nullable depending on
 * outcome, where two records let each carry exactly what's relevant.
 */
public final class PaymentEvents {

    private PaymentEvents() {}

    public record PaymentCompletedEvent(
            UUID orderId,
            UUID transactionId,
            Instant occurredAt
    ) {}

    public record PaymentFailedEvent(
            UUID orderId,
            String failureReason,
            Instant occurredAt
    ) {}
}
