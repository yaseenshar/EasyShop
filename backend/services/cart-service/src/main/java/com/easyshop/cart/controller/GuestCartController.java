package com.easyshop.cart.controller;

import com.easyshop.cart.dto.CartDtos.AddItemRequest;
import com.easyshop.cart.dto.CartDtos.CartResponse;
import com.easyshop.cart.dto.CartDtos.GuestTokenResponse;
import com.easyshop.cart.dto.CartDtos.UpdateQuantityRequest;
import com.easyshop.cart.repository.CartKey;
import com.easyshop.cart.service.CartService;
import com.easyshop.common.dto.response.ApiResponse;
import com.easyshop.common.metrics.BusinessMetrics;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * The anonymous half of the cart API.
 *
 * WHY THE TOKEN TRAVELS IN A HEADER and not in the path. The token is the sole
 * credential for a guest cart - anyone holding it holds the cart - so it must
 * be treated like one. A path like /api/v1/cart/guest/{token}/items would print
 * that credential into access logs, proxy logs, browser history and the
 * Referer header of every outbound link on the page. Headers appear in none of
 * those by default. A cookie was the other candidate and is ruled out
 * structurally: the gateway strips inbound Cookie headers
 * (RemoveRequestHeader=Cookie in its default-filters), so a cookie would never
 * reach this service.
 *
 * MINTED SERVER-SIDE, NEVER CLIENT-CHOSEN. createGuestCart() is the only source
 * of tokens. If callers could pick their own, guessing or enumerating another
 * shopper's token would be trivial and every guest cart would be readable by
 * anyone - the anonymous equivalent of the IDOR that CartController's
 * identity-derived key design exists to prevent. A random UUID carries 122 bits
 * of entropy, which is not enumerable.
 *
 * Note there is no ownership check anywhere here, and none is possible: for an
 * anonymous cart, possession of the token IS the authorization. That is exactly
 * why the token must be unguessable and must not leak into a URL.
 */
@RestController
@RequestMapping("/api/v1/cart/guest")
public class GuestCartController {

    static final String TOKEN_HEADER = "X-Cart-Token";

    /**
     * Denominator for the guest conversion rate; see CartService's GUEST_MERGES.
     *
     * NOT named ".created" - verified the hard way. OpenMetrics reserves the
     * _created suffix for a counter's creation timestamp, so the Prometheus
     * client strips it: "easyshop.carts.guest.created" was published as
     * easyshop_carts_guest_total, silently losing the last word and colliding
     * with any other easyshop.carts.guest.* meter. Nothing errors; the metric
     * just quietly has a different name than the code says. Avoid _created,
     * _total, _sum, _count and _bucket as trailing words in meter names.
     */
    private static final String GUEST_CARTS_ISSUED = "easyshop.carts.guest.issued";

    private final CartService cartService;
    private final BusinessMetrics businessMetrics;

    public GuestCartController(CartService cartService, BusinessMetrics businessMetrics) {
        this.cartService = cartService;
        this.businessMetrics = businessMetrics;
    }

    /**
     * Mints a new guest cart token. Creates no Redis key - the cart springs
     * into existence on the first item added, so a bot hammering this endpoint
     * burns CPU but stores nothing.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<GuestTokenResponse>> createGuestCart() {
        String token = UUID.randomUUID().toString();
        businessMetrics.increment(GUEST_CARTS_ISSUED);
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.success("Guest cart created", new GuestTokenResponse(token)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<CartResponse>> getCart(
            @RequestHeader(TOKEN_HEADER) String token) {
        return ResponseEntity.ok(ApiResponse.success(cartService.getCart(guestCart(token))));
    }

    @PostMapping("/items")
    public ResponseEntity<ApiResponse<CartResponse>> addItem(
            @RequestHeader(TOKEN_HEADER) String token,
            @Valid @RequestBody AddItemRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Item added",
                cartService.addItem(guestCart(token), request)));
    }

    @PutMapping("/items/{productId}")
    public ResponseEntity<ApiResponse<CartResponse>> updateQuantity(
            @RequestHeader(TOKEN_HEADER) String token,
            @PathVariable UUID productId,
            @Valid @RequestBody UpdateQuantityRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Quantity updated",
                cartService.updateQuantity(guestCart(token), productId, request.quantity())));
    }

    @DeleteMapping("/items/{productId}")
    public ResponseEntity<ApiResponse<CartResponse>> removeItem(
            @RequestHeader(TOKEN_HEADER) String token,
            @PathVariable UUID productId) {
        return ResponseEntity.ok(ApiResponse.success("Item removed",
                cartService.removeItem(guestCart(token), productId)));
    }

    @DeleteMapping
    public ResponseEntity<ApiResponse<Void>> clearCart(@RequestHeader(TOKEN_HEADER) String token) {
        cartService.clearCart(guestCart(token));
        return ResponseEntity.ok(ApiResponse.success("Cart cleared", null));
    }

    /**
     * CartKey.Guest validates the token's shape (non-blank, no ':', length
     * capped), so a malformed or structure-injecting token is rejected here
     * before it can be turned into a Redis key.
     */
    private CartKey.Guest guestCart(String token) {
        return CartKey.guest(token);
    }
}
