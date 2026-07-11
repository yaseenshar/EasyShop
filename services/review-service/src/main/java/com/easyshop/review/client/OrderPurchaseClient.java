package com.easyshop.review.client;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.util.UUID;

/**
 * HTTP interface client for order-service's purchase-check endpoint.
 *
 * VERSION-DISCIPLINE NOTE (verified 12 July 2026): early Spring Boot 4
 * milestone tutorials show an @HttpServiceClient annotation placed on this
 * interface - that annotation was REMOVED before GA (Spring Framework 7.0
 * M9+) because interface-side configuration caused circular-dependency
 * problems when the same interface jar is shared between client and
 * server. The current, correct pattern is exactly this: a bare interface
 * with @HttpExchange methods, registered from the CONSUMER side via
 * @ImportHttpServices on a @Configuration class (see HttpClientConfig).
 * If you see @HttpServiceClient in a tutorial, you're reading milestone-
 * era content - the same category of tell as javax.* imports.
 *
 * Note this is deliberately a MINIMAL interface: one method, one question.
 * Interface Segregation - review-service gets exactly the capability it
 * needs from order-service and nothing more, which keeps the coupling
 * surface as small as the coupling decision (sync verification, Part B
 * rationale) requires.
 */
@HttpExchange("/internal/purchases")
public interface OrderPurchaseClient {

    @GetExchange("/users/{userId}/products/{productId}")
    PurchaseCheckResponse hasPurchased(@PathVariable UUID userId,
                                       @PathVariable UUID productId);

    record PurchaseCheckResponse(boolean purchased) {}
}