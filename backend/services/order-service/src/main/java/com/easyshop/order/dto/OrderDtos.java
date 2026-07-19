package com.easyshop.order.dto;

import com.easyshop.order.entity.Order;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.data.domain.Page;

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
            UUID shippingAddressId,
            List<OrderItemResponse> items,
            Instant createdAt
    ) {
        public static OrderResponse from(Order order) {
            return new OrderResponse(
                    order.getId(),
                    order.getStatus().name(),
                    order.getTotalAmount(),
                    order.getCurrency(),
                    order.getShippingAddressId(),
                    order.getItems().stream().map(OrderItemResponse::from).toList(),
                    order.getCreatedAt()
            );
        }
    }

    /** No product name here - order-service doesn't own catalog data; the
     *  caller resolves names from catalog-service if it needs to display them. */
    public record OrderItemResponse(
            UUID productId,
            int quantity,
            BigDecimal unitPrice
    ) {
        public static OrderItemResponse from(com.easyshop.order.entity.OrderItem item) {
            return new OrderItemResponse(item.getProductId(), item.getQuantity(), item.getUnitPrice());
        }
    }

    public record PagedResponse<T>(
            List<T> content,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {
        public static <T> PagedResponse<T> from(Page<T> page) {
            return new PagedResponse<>(
                    page.getContent(), page.getNumber(), page.getSize(),
                    page.getTotalElements(), page.getTotalPages());
        }
    }
}