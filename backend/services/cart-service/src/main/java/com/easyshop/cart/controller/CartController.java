package com.easyshop.cart.controller;

import com.easyshop.cart.dto.CartDtos.AddItemRequest;
import com.easyshop.cart.dto.CartDtos.CartResponse;
import com.easyshop.cart.dto.CartDtos.UpdateQuantityRequest;
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
        return ResponseEntity.ok(ApiResponse.success(cartService.getCart(userId(jwt))));
    }

    @PostMapping("/items")
    public ResponseEntity<ApiResponse<CartResponse>> addItem(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AddItemRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success("Item added", cartService.addItem(userId(jwt), request)));
    }

    @PutMapping("/items/{productId}")
    public ResponseEntity<ApiResponse<CartResponse>> updateQuantity(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID productId,
            @Valid @RequestBody UpdateQuantityRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Quantity updated",
                cartService.updateQuantity(userId(jwt), productId, request.quantity())));
    }

    @DeleteMapping("/items/{productId}")
    public ResponseEntity<ApiResponse<CartResponse>> removeItem(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID productId) {
        return ResponseEntity.ok(
                ApiResponse.success("Item removed", cartService.removeItem(userId(jwt), productId)));
    }

    @DeleteMapping
    public ResponseEntity<ApiResponse<Void>> clearCart(@AuthenticationPrincipal Jwt jwt) {
        cartService.clearCart(userId(jwt));
        return ResponseEntity.ok(ApiResponse.success("Cart cleared", null));
    }

    private UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}