package com.easyshop.order.controller;

import com.easyshop.common.dto.response.ApiResponse;
import com.easyshop.common.exception.ResourceNotFoundException;
import com.easyshop.common.idempotency.Idempotent;
import com.easyshop.order.dto.OrderDtos.CreateOrderRequest;
import com.easyshop.order.dto.OrderDtos.OrderResponse;
import com.easyshop.order.dto.OrderDtos.PagedResponse;
import com.easyshop.order.entity.Order;
import com.easyshop.order.entity.OrderSagaState;
import com.easyshop.order.repository.OrderRepository;
import com.easyshop.order.repository.OrderSagaRepository;
import com.easyshop.order.saga.OrderSagaOrchestrator;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
     * in the entire system.
     *
     * THREE layers, in order of who catches what:
     *   1. @Idempotent (Redis, fast path) - short-circuits a duplicate BEFORE
     *      this method runs at all, replaying the original response verbatim
     *      (same status, same body, "Idempotent-Replayed: true").
     *   2. orders.idempotency_key UNIQUE (DB, backstop) - catches the window
     *      where Redis failed OPEN or the SET-NX/GET race slipped a second
     *      request through. The pre-check below (findByIdempotencyKey) is the
     *      FAST version of this for the common case; the constraint is what
     *      actually enforces it against a genuine race.
     *   3. THE CATCH BLOCK below - turns that constraint failure into "return
     *      the existing order", not a 409/500. Without this, a raced duplicate
     *      would see a bare conflict response instead of its order - correct
     *      in that no duplicate is created, but unfriendly, and not what
     *      "idempotent" should mean to the caller.
     *
     * Idempotency-Key is a CLIENT-supplied header, generated once per checkout
     * attempt (e.g. when the user lands on the checkout page, the Angular
     * frontend generates a UUID and reuses it across retries of the SAME
     * attempt - a page refresh or "new" checkout click generates a fresh key).
     */
    @Idempotent(required = true)
    @PostMapping
    public ResponseEntity<ApiResponse<OrderResponse>> createOrder(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {

        // Fast pre-check, before any side effects - avoids even attempting the
        // write (and thus a transactional round-trip) for the common case.
        var existing = orderRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return ResponseEntity.ok(
                    ApiResponse.success("Order already exists for this request", OrderResponse.from(existing.get())));
        }

        UUID userId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
        BigDecimal total = request.items().stream()
                .map(item -> item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        try {
            Order order = orchestrator.createOrderAndStartSaga(userId, total, request, idempotencyKey);
            return ResponseEntity
                    .status(HttpStatus.ACCEPTED) // 202, not 201 - the order is accepted
                    // for processing, not yet confirmed. This distinction matters: a
                    // client should poll GET /orders/{id} or listen on a websocket
                    // for the terminal CONFIRMED/CANCELLED state.
                    .body(ApiResponse.success("Order accepted, processing", OrderResponse.from(order)));
        } catch (DataIntegrityViolationException dup) {
            // Layer 3: a concurrent duplicate won the insert first. Return THAT
            // order - do not start a second saga, do not surface a bare 409.
            Order existingOrder = orderRepository.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> dup);
            return ResponseEntity.ok(
                    ApiResponse.success("Order already exists for this request", OrderResponse.from(existingOrder)));
        }
    }

    /**
     * Operator-triggered refund - see OrderSagaOrchestrator#triggerRefund's
     * javadoc for why this isn't automatic. Only meaningful once payment
     * actually succeeded (CONFIRMED), and idempotent against a double-click:
     * a second call against an already-requested refund returns 200 without
     * publishing a second RefundPaymentCommand (OrderSagaState#markRefundRequested).
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{orderId}/refund")
    @Transactional
    public ResponseEntity<ApiResponse<OrderResponse>> refundOrder(@PathVariable UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        OrderSagaState saga = sagaRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Unknown saga: " + orderId));

        if (order.getStatus() != Order.OrderStatus.CONFIRMED) {
            throw new IllegalStateException(
                    "Only a CONFIRMED order can be refunded (current status: " + order.getStatus() + ")");
        }

        boolean triggered = orchestrator.triggerRefund(order, saga);
        String message = triggered ? "Refund requested" : "Refund already requested for this order";
        return ResponseEntity.ok(ApiResponse.success(message, OrderResponse.from(order)));
    }

    /**
     * Own orders only - query-scoped ownership (README §preferred approach):
     * the WHERE clause does the work, so there is nothing to leak and nothing
     * to bypass. ADMIN uses a real admin surface if/when "all orders" is needed.
     */
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<PagedResponse<OrderResponse>>> listMyOrders(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID userId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
        var result = orderRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, Math.min(size, 100)))
                .map(OrderResponse::from);
        return ResponseEntity.ok(ApiResponse.success(PagedResponse.from(result)));
    }

    // Admin bypass + ownership: existence of the id is not leaked to a
    // non-owner (403, not 404-vs-404) - see OrderAccess javadoc for the
    // trade-off against query-scoped 404-for-both.
    @PreAuthorize("hasRole('ADMIN') or @orderAccess.isOwner(#orderId, authentication.name)")
    @GetMapping("/{orderId}")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<OrderResponse>> getOrder(@PathVariable UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new com.easyshop.common.exception.ResourceNotFoundException(
                        "Order not found: " + orderId));
        return ResponseEntity.ok(ApiResponse.success(OrderResponse.from(order)));
    }
}