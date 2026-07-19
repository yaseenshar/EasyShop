package com.easyshop.inventory.entity;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Entity
@Table(name = "product_stock")
public class ProductStock {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "product_id", nullable = false, unique = true)
    private UUID productId;

    @Column(name = "available_qty", nullable = false)
    private int availableQty;

    @Column(name = "reserved_qty", nullable = false)
    private int reservedQty;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProductStock() {}

    public static ProductStock createNew(UUID productId, int initialQty) {
        ProductStock productStock = new ProductStock();
        productStock.productId = productId;
        productStock.availableQty = initialQty;
        productStock.reservedQty = 0;
        return productStock;
    }

    public boolean tryReserve(int quantity) {
        if (availableQty < quantity) {
            return false;
        }

        availableQty -= quantity;
        reservedQty += quantity;
        return true;
    }

    public void confirmReservation(int quantity) {
        if (reservedQty < quantity) {
            throw new IllegalArgumentException("Can not confirm more than reserved: requested=" + quantity + " reserved=" + reservedQty);
        }

        reservedQty -= quantity;
    }

    public void releaseReservation(int quantity) {

        if (reservedQty < quantity) {
            throw new IllegalArgumentException("Can not release more than reserved: requested=" + quantity + " reserved=" + reservedQty);
        }

        reservedQty -= quantity;
        availableQty += quantity;

    }

    @PrePersist
    @PreUpdate
    public void touch() {
        updatedAt = Instant.now();
    }
}
