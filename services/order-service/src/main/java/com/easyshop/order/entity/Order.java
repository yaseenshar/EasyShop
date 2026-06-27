package com.easyshop.order.entity;

import jakarta.persistence.*;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
@Getter
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderStatus status;


    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "shipping_address_id")
    private UUID shippingAddressId;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Order() {
    }

    public static Order createNew(UUID userId, BigDecimal totalAmount, UUID shippingAddressId, String idempotencyKey) {
        Order order = new Order();
        order.userId = userId;
        order.totalAmount = totalAmount;
        order.shippingAddressId = shippingAddressId;
        order.idempotencyKey = idempotencyKey;
        order.status = OrderStatus.PENDING;

        return order;
    }

    public void addItem(UUID productId, int quantity, BigDecimal unitPrice) {

        OrderItem orderItem = OrderItem.creatNew(this, productId, quantity, unitPrice);
        this.items.add(orderItem);
    }

    public void transitionTo(OrderStatus newStatus) {

        if (!this.status.canTransitionTo(newStatus)) {
            throw new IllegalArgumentException("Invalid order status transition from " + this.status + " to " + newStatus);
        }
        this.status = newStatus;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate(){
        this.updatedAt = Instant.now();
    }

    public enum OrderStatus {

        PENDING,
        RESERVING_STOCK,
        CHARGING_PAYMENT,
        CONFIRMING_STOCK,
        NOTIFYING,
        CONFIRMED,
        COMPENSATING,
        CANCELLED;

        public boolean canTransitionTo(OrderStatus orderStatus) {
            return switch (this) {
                case PENDING -> orderStatus == RESERVING_STOCK || orderStatus == CANCELLED;
                case RESERVING_STOCK -> orderStatus == CHARGING_PAYMENT || orderStatus == CANCELLED;
                case CHARGING_PAYMENT -> orderStatus == CONFIRMING_STOCK || orderStatus == COMPENSATING;
                case CONFIRMING_STOCK -> orderStatus == NOTIFYING || orderStatus == COMPENSATING;
                case NOTIFYING -> orderStatus == CONFIRMED;
                case COMPENSATING -> orderStatus == CANCELLED;
                case CONFIRMED, CANCELLED -> false;
            };
        }
    }
}
