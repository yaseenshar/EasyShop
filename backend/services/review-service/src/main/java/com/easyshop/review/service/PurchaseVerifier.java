package com.easyshop.review.service;

import com.easyshop.review.client.OrderPurchaseClient;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.log4j.Log4j2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The graceful-degradation centerpiece, extracted into its own bean so
 * the @CircuitBreaker proxy actually intercepts the call (see the comment
 * in ReviewService.submitReview for the self-invocation trap this avoids).
 *
 * When order-service is healthy: accurate verified/unverified badges.
 * When it's down or the breaker is OPEN: the fallback returns false - the
 * review is accepted WITHOUT the badge rather than rejected. Degrade the
 * feature (badge accuracy), never the function (accepting reviews).
 *
 * Natural completion of the pattern (follow-up, not built here): a
 * background reconciliation job re-checks recent unverified reviews once
 * order-service recovers and upgrades their badges.
 */
@Log4j2
@Component
public class PurchaseVerifier {

    private final OrderPurchaseClient orderPurchaseClient;

    public PurchaseVerifier(OrderPurchaseClient orderPurchaseClient) {
        this.orderPurchaseClient = orderPurchaseClient;
    }

    @CircuitBreaker(name = "orderService", fallbackMethod = "verificationUnavailable")
    public boolean isVerifiedPurchase(UUID userId, UUID productId) {
        return orderPurchaseClient.hasPurchased(userId, productId).purchased();
    }

    public boolean verificationUnavailable(UUID userId, UUID productId, Throwable t) {
        log.warn("order-service unavailable for purchase verification (user={}, product={}): {}"
                + " - accepting review as unverified", userId, productId, t.getMessage());
        return false;
    }
}