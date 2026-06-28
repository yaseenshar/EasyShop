package com.easyshop.payment.entity;

import jakarta.persistence.*;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Entity
@Table(name = "payment_transactions")
public class PaymentTransaction {


    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "gateway_reference")
    private String gatewayReference;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentTransaction() {}

    public static PaymentTransaction createProcessing(UUID orderId, UUID userId, BigDecimal amount,
                                                      String currency, String idempotencyKey) {
        PaymentTransaction tx = new PaymentTransaction();
        tx.orderId = orderId;
        tx.userId = userId;
        tx.amount = amount;
        tx.currency = currency;
        tx.idempotencyKey = idempotencyKey;
        tx.status = PaymentStatus.PROCESSING;
        return tx;
    }

    public void markSucceeded(String gatewayReference) {
        this.status = PaymentStatus.SUCCEEDED;
        this.gatewayReference = gatewayReference;
    }

    public void markFailed(String reason) {
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public enum PaymentStatus {
        PROCESSING, SUCCEEDED, FAILED
    }
}