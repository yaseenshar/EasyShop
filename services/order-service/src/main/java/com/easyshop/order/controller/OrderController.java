package com.easyshop.order.controller;

import com.easyshop.common.dto.response.ApiResponse;
import com.easyshop.order.dto.OrderDtos.CreateOrderRequest;
import com.easyshop.order.dto.OrderDtos.OrderResponse;
import com.easyshop.order.entity.Order;
import com.easyshop.order.entity.OrderSagaState;
import com.easyshop.order.repository.OrderRepository;
import com.easyshop.order.repository.OrderSagaRepository;
import com.easyshop.order.saga.OrderSagaOrchestrator;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderRepository orderRepository;
    private final OrderSagaRepository sagaRepository;
    private final OrderSagaOrchestrator orchestrator;

    public OrderController(OrderRepository orderRepository,
                           OrderSagaRepository sagaRepository,
                           OrderSagaOrchestrator orchestrator) {
        this.orderRepository = orderRepository;
        this.sagaRepository = sagaRepository;
        this.orchestrator = orchestrator;
    }

    /**
     * The checkout endpoint - the single most important idempotency boundary
     * in the entire system (full deep-dive coming in Phase 4, but the
     * essential mechanism is introduced here since the saga depends on it).
     *
     * Idempotency-Key is a CLIENT-supplied header, generated once per checkout
     * attempt (e.g. when the user lands on the checkout page, the Angular
     * frontend generates a UUID and reuses it across retries of the SAME
     * attempt - a page refresh or "new" checkout click generates a fresh key).
     *
     * If a request with the same key arrives again (double-click, client
     * retry after a timeout, etc.), we return the EXISTING order instead of
     * creating a duplicate - this is what makes a POST endpoint safe to retry,
     * which is otherwise impossible for a naturally non-idempotent operation
     * like "create an order."
     */
    @PostMapping
    @Transactional
    public ResponseEntity<ApiResponse<OrderResponse>> createOrder(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {

        // Idempotency check FIRST, before any side effects.
        var existing = orderRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return ResponseEntity.ok(
                    ApiResponse.success("Order already exists for this request", OrderResponse.from(existing.get())));
        }

        UUID userId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));

        BigDecimal total = request.items().stream()
                .map(item -> item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Order order = Order.createNew(userId, total, request.shippingAddressId(), idempotencyKey);
        Order finalOrder = order;
        request.items().forEach(item ->
                finalOrder.addItem(item.productId(), item.quantity(), item.unitPrice()));

        order = orderRepository.save(finalOrder);

        OrderSagaState saga = OrderSagaState.createNew(order.getId());
        saga = sagaRepository.save(saga);

        // Kicks off the saga - writes the first outbox event in this same
        // @Transactional method, so order creation and "saga started" are
        // atomic with respect to each other.
        orchestrator.startSaga(order, saga);

        return ResponseEntity
                .status(HttpStatus.ACCEPTED) // 202, not 201 - the order is accepted
                // for processing, not yet confirmed.
                // This distinction matters: a client
                // should poll GET /orders/{id} or
                // listen on a websocket for the
                // terminal CONFIRMED/CANCELLED state.
                .body(ApiResponse.success("Order accepted, processing", OrderResponse.from(order)));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrder(@PathVariable UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new com.easyshop.common.exception.ResourceNotFoundException(
                        "Order not found: " + orderId));
        return ResponseEntity.ok(ApiResponse.success(OrderResponse.from(order)));
    }
}