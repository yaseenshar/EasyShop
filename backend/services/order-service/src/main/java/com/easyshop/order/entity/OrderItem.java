package com.easyshop.order.entity;

import jakarta.persistence.*;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    protected OrderItem() {
    }

    public static OrderItem creatNew(Order order, UUID productId, int quantity, BigDecimal unitPrice) {

        OrderItem orderItem = new OrderItem();
        orderItem.order = order;
        orderItem.productId = productId;
        orderItem.quantity = quantity;
        orderItem.unitPrice = unitPrice;

        return orderItem;
    }
}
