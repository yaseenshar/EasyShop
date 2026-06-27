package com.easyshop.order.entity;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Entity
@Table(name = "order_saga_state")
public class OrderSagaState {

    @Id
    @Column(name = "order_id")
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_step", nullable = false, length = 30)
    private Order.OrderStatus currentStep;

    @Column(name = "reservation_id", nullable = false)
    private UUID reservationId;

    @Column(name = "payment_transaction_id", nullable = false)
    private UUID paymentTransactionId;

    @Column(name = "compensations_run", nullable = false)
    private List<String> compansationsRun = new ArrayList<>();

    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OrderSagaState() {
    }

    public static OrderSagaState createNew(UUID orderId) {
        OrderSagaState state = new OrderSagaState();
        state.orderId = orderId;
        state.currentStep = Order.OrderStatus.PENDING;
        return state;
    }

    public void advanceTo(Order.OrderStatus step){
        this.currentStep = step;
        this.updatedAt = Instant.now();
    }

    public void recordReservation(UUID reservationId) {
        this.reservationId = reservationId;
    }

    public void recordPayment(UUID paymentTransactionId) {
        this.paymentTransactionId  =paymentTransactionId;

    }

    public void recordCompensation(String compensationStep) {
        this.compansationsRun.add(compensationStep);
    }

    public boolean hasComensationRun(String compensationStep) {
        return this.compansationsRun.contains(compensationStep);
    }

    @PrePersist
    @PreUpdate
    public void touch() {
        this.updatedAt = Instant.now();
    }
}
