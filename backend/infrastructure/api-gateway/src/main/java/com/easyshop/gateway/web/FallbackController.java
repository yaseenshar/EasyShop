package com.easyshop.gateway.web; // align with the gateway's package layout

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import com.easyshop.common.dto.response.ApiResponse;

/**
 * Gateway fallback endpoints, targeted by `fallbackUri: forward:/fallback/*`.
 *
 * WHY 503 AND NOT 200:
 * A fallback that answers 200 with empty data teaches every client that
 * degradation is success. The SPA would then render "No reviews yet" when the
 * truth is "reviews are unavailable" — a quiet lie about the product. 503 +
 * a distinct errorCode lets the UI say the honest thing. This is §4.12's
 * "degrade the feature, not the function" applied at the edge.
 *
 * Retry-After tells well-behaved clients when to come back; align the value
 * with waitDurationInOpenState.
 *
 * @RequestMapping with no method restriction is deliberate: forward preserves
 * the ORIGINAL request method, so a failed POST /api/v1/orders/checkout
 * arrives here as a POST. A @GetMapping-only fallback returns 405 and you get
 * a confusing "Method Not Allowed" instead of your fallback body.
 */
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    private static final String RETRY_AFTER = "10"; // seconds; match waitDurationInOpenState

    private Mono<ResponseEntity<ApiResponse<Void>>> unavailable(String message, String errorCode) {
        return Mono.just(ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", RETRY_AFTER)
                .body(ApiResponse.error(message, errorCode)));
    }

    @RequestMapping("/catalog")
    public Mono<ResponseEntity<ApiResponse<Void>>> catalog() {
        return unavailable("Product catalog is temporarily unavailable.", "CATALOG_UNAVAILABLE");
    }

    @RequestMapping("/user")
    public Mono<ResponseEntity<ApiResponse<Void>>> user() {
        return unavailable("Account service is temporarily unavailable.", "USER_UNAVAILABLE");
    }

    @RequestMapping("/order")
    public Mono<ResponseEntity<ApiResponse<Void>>> order() {
        // NEVER fabricate order state. A checkout that fell back may or may not
        // have started a saga; the honest answer is "unknown, check history".
        return unavailable(
                "Orders are temporarily unavailable. If you just placed an order, "
                        + "check your order history before retrying.",
                "ORDER_UNAVAILABLE");
    }

    @RequestMapping("/cart")
    public Mono<ResponseEntity<ApiResponse<Void>>> cart() {
        // Redis is the PRIMARY store for carts (§4.9) — an empty-cart fallback
        // would be data-loss theatre, not degradation.
        return unavailable("Your cart is temporarily unavailable.", "CART_UNAVAILABLE");
    }

    @RequestMapping("/review")
    public Mono<ResponseEntity<ApiResponse<Void>>> review() {
        // Distinct from "no reviews" — see the class javadoc.
        return unavailable("Reviews are temporarily unavailable.", "REVIEWS_UNAVAILABLE");
    }
}