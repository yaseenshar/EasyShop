package com.easyshop.cart.controller;

import com.easyshop.cart.dto.CartDtos.AddItemRequest;
import com.easyshop.cart.dto.CartDtos.CartResponse;
import com.easyshop.cart.dto.CartDtos.UpdateQuantityRequest;
import com.easyshop.cart.repository.CartKey;
import com.easyshop.cart.service.CartService;
import com.easyshop.common.dto.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Note the resource design: the cart is a SINGLETON resource per user -
 * there is no {cartId} anywhere. The cart's identity IS the caller's
 * identity (JWT sub claim), which eliminates an entire class of IDOR
 * (insecure direct object reference) bugs: you cannot fetch or mutate
 * someone else's cart because no cart is addressable by ID at all.
 * Identity-derived resources beat access-checked resources when the
 * relationship is genuinely 1:1.
 */
@RestController
@RequestMapping("/api/v1/cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<CartResponse>> getCart(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ApiResponse.success(cartService.getCart(cartOf(jwt))));
    }

    @PostMapping("/items")
    public ResponseEntity<ApiResponse<CartResponse>> addItem(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AddItemRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success("Item added", cartService.addItem(cartOf(jwt), request)));
    }

    @PutMapping("/items/{productId}")
    public ResponseEntity<ApiResponse<CartResponse>> updateQuantity(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID productId,
            @Valid @RequestBody UpdateQuantityRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Quantity updated",
                cartService.updateQuantity(cartOf(jwt), productId, request.quantity())));
    }

    @DeleteMapping("/items/{productId}")
    public ResponseEntity<ApiResponse<CartResponse>> removeItem(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID productId) {
        return ResponseEntity.ok(
                ApiResponse.success("Item removed", cartService.removeItem(cartOf(jwt), productId)));
    }

    @DeleteMapping
    public ResponseEntity<ApiResponse<Void>> clearCart(@AuthenticationPrincipal Jwt jwt) {
        cartService.clearCart(cartOf(jwt));
        return ResponseEntity.ok(ApiResponse.success("Cart cleared", null));
    }

    /**
     * Folds the caller's guest cart into their account cart after login, and
     * deletes the guest key.
     *
     * This lives on the AUTHENTICATED controller by design: merging needs a
     * verified user identity for the destination cart, and the guest token
     * alone must never be able to name one. Anyone can present any guest token
     * here - possession is the only claim a guest cart supports - but the
     * destination is always the JWT's own cart, so the worst a stolen token
     * achieves is donating its contents to the thief's cart.
     */
    @PostMapping("/merge")
    public ResponseEntity<ApiResponse<CartResponse>> mergeGuestCart(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(GuestCartController.TOKEN_HEADER) String guestToken) {
        return ResponseEntity.ok(ApiResponse.success("Guest cart merged",
                cartService.mergeGuestIntoSession(CartKey.guest(guestToken), cartOf(jwt))));
    }

    /**
     * Every endpoint here addresses the SESSION key-space, derived from the JWT
     * sub claim. Guest carts reach the same service methods through
     * CartKey.guest(...) once the anonymous endpoints land - the service and
     * repository already support them.
     */
    private CartKey.Session cartOf(Jwt jwt) {
        return CartKey.session(UUID.fromString(jwt.getSubject()));
    }
}