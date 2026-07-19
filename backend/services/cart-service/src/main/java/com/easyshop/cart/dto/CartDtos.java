package com.easyshop.cart.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CartDtos {

    private CartDtos() {}

    /**
     * The stored shape - serialized to JSON as one hash field value under
     * cart:{userId}, field key = productId.
     *
     * priceSnapshot/nameSnapshot are DISPLAY data captured at add-time so
     * the cart renders without N catalog lookups per view. They are never
     * authoritative: checkout re-derives current prices (see the Phase 8
     * staleness rationale). addedAt supports "added 3 days ago" UX and
     * debugging.
     */
    public record CartItem(
            UUID productId,
            String nameSnapshot,
            BigDecimal priceSnapshot,
            int quantity,
            Instant addedAt
    ) {
        public CartItem withQuantity(int newQuantity) {
            return new CartItem(productId, nameSnapshot, priceSnapshot, newQuantity, addedAt);
        }
    }

    public record AddItemRequest(
            @NotNull UUID productId,
            @NotBlank String name,
            @NotNull @PositiveOrZero BigDecimal price,
            @Min(1) @Max(99) int quantity
    ) {}

    public record UpdateQuantityRequest(
            @Min(1) @Max(99) int quantity
    ) {}

    public record CartResponse(
            List<CartItem> items,
            int totalItems,
            BigDecimal subtotal
    ) {
        public static CartResponse of(List<CartItem> items) {
            int totalItems = items.stream().mapToInt(CartItem::quantity).sum();
            BigDecimal subtotal = items.stream()
                    .map(i -> i.priceSnapshot().multiply(BigDecimal.valueOf(i.quantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return new CartResponse(items, totalItems, subtotal);
        }
    }
}