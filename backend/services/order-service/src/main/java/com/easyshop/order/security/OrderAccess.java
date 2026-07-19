package com.easyshop.order.security; // align with the service's actual package layout

import java.util.UUID;

import com.easyshop.order.repository.OrderRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bean-based ownership check for @PreAuthorize — the answer to the interview
 * probe "where does authorization logic live when SpEL can't express it?".
 *
 * Usage on the admin-or-owner endpoints:
 *
 *   @PreAuthorize("hasRole('ADMIN') or @orderAccess.isOwner(#orderId, authentication.name)")
 *   @GetMapping("/api/v1/orders/{orderId}")
 *   public ApiResponse<OrderResponse> getOrder(@PathVariable UUID orderId) { ... }
 *
 * REQUIRES the -parameters compiler flag for #orderId to resolve at runtime
 * (Spring Framework 6.1+ removed the bytecode fallback — README traps). Boot's
 * parent POM sets it; any module overriding maven-compiler-plugin loses it.
 *
 * TRADE-OFF, STATED (choose consciously): this path answers 403 for someone
 * else's order, which confirms to the caller that the id EXISTS. If that
 * existence leak matters, prefer folding ownership into the query for plain
 * customer reads:
 *
 *   orderRepository.findByIdAndCustomerKeycloakId(orderId, authentication.getName())
 *       .orElseThrow(OrderNotFoundException::new)
 *
 * -> 404 for both "missing" and "not yours"; nothing leaks, no SpEL, no AOP.
 * Keep this bean for the endpoints where an ADMIN bypass must coexist with
 * ownership in a single rule.
 */
@Component("orderAccess")
public class OrderAccess {

    private final OrderRepository orderRepository; // align with your repository type

    public OrderAccess(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional(readOnly = true)
    public boolean isOwner(UUID orderId, String subject) {
        // subject = authentication.getName() = the JWT sub claim — the same
        // Keycloak-ID linkage the platform already keys users on (§4.4).
        return orderRepository.findById(orderId)
                .map(order -> subject.equals(order.getUserId().toString()))
                .orElse(false);
    }
}