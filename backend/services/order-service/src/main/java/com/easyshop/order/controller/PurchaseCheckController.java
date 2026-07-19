package com.easyshop.order.controller;

import com.easyshop.order.repository.OrderRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * ADD TO ORDER-SERVICE (drop into com.easyshop.order.controller).
 *
 * Internal endpoint consumed by review-service's OrderPurchaseClient.
 * "/internal/**" paths are deliberately NOT routed by the API Gateway
 * (Phase 1 routes only expose /api/v1/orders/**) - so this endpoint is
 * reachable service-to-service inside the cluster but has no public
 * ingress path. That's the network-level guard; in Phase 8 (Kubernetes/
 * Istio), mesh-level mTLS + AuthorizationPolicy makes "only review-service
 * may call /internal/purchases" an enforced rule rather than a convention.
 *
 * Requires one repository addition on OrderRepository:
 *
 *   @Query("""
 *       SELECT COUNT(o) > 0 FROM Order o JOIN o.items i
 *       WHERE o.userId = :userId AND i.productId = :productId
 *       AND o.status = 'CONFIRMED'
 *       """)
 *   boolean hasConfirmedPurchase(@Param("userId") UUID userId,
 *                                 @Param("productId") UUID productId);
 *
 * Only CONFIRMED orders count - a cancelled or in-flight order is not a
 * purchase. Backed by the existing idx_orders_user_id index plus the
 * order_items order_id FK index from the Phase 3 migration.
 */
@RestController
@RequestMapping("/internal/purchases")
public class PurchaseCheckController {

    private final OrderRepository orderRepository;

    public PurchaseCheckController(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @GetMapping("/users/{userId}/products/{productId}")
    public PurchaseCheckResponse hasPurchased(@PathVariable UUID userId,
                                              @PathVariable UUID productId) {
        return new PurchaseCheckResponse(
                orderRepository.hasConfirmedPurchase(userId, productId));
    }

    // Shape matches OrderPurchaseClient.PurchaseCheckResponse in
    // review-service - same JSON on the wire, independently declared on
    // each side. For a single-field internal contract this duplication is
    // acceptable; if it grows, move it to common-lib like SagaMessages.
    public record PurchaseCheckResponse(boolean purchased) {}
}