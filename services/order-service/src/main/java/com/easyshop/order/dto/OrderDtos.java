package com.easyshop.order.dto;

import com.easyshop.order.entity.Order;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class OrderDtos {

    private OrderDtos() {}

    public record CreateOrderRequest(
            @NotEmpty List<OrderLineRequest> items,
            @NotNull UUID shippingAddressId
    ) {
        public record OrderLineRequest(
                @NotNull UUID productId,
                @Positive int quantity,
                @NotNull BigDecimal unitPrice
        ) {}
    }

    public record OrderResponse(
            UUID id,
            String status,
            BigDecimal totalAmount,
            String currency,
            Instant createdAt
    ) {
        public static OrderResponse from(Order order) {
            return new OrderResponse(
                    order.getId(),
                    order.getStatus().name(),
                    order.getTotalAmount(),
                    order.getCurrency(),
                    order.getCreatedAt()
            );
        }
    }
}