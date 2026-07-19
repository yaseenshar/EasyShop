package com.easyshop.cart.service;

import com.easyshop.cart.dto.CartDtos.AddItemRequest;
import com.easyshop.cart.dto.CartDtos.CartItem;
import com.easyshop.cart.dto.CartDtos.CartResponse;
import com.easyshop.cart.repository.CartRepository;
import com.easyshop.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Note there is NO @Transactional anywhere in this service - deliberately.
 * Redis single commands are individually atomic and there is no relational
 * transaction manager in this service to enlist. (Redis MULTI/EXEC exists
 * for multi-command atomicity, and Spring supports it via
 * SessionCallback - our operations are all single-command, so it would be
 * ceremony without benefit. Knowing WHY the annotation is absent beats
 * cargo-culting it on.)
 *
 * GUEST CARTS - designed but not built: the extension is a second key
 * space cart:guest:{cartToken} (token minted for anonymous sessions,
 * stored client-side), plus a merge-on-login endpoint that HGETALLs the
 * guest cart, put()s each item into the user cart with quantity-summing
 * on collision, then deletes the guest key. The merge policy (sum
 * quantities vs prefer-user-cart) is a product decision; sum is the
 * common default. Left as a follow-up so this phase stays focused, but
 * be ready to whiteboard it - "how do you handle guest checkout" is a
 * frequent e-commerce interview probe.
 */
@Service
public class CartService {

    private final CartRepository cartRepository;

    public CartService(CartRepository cartRepository) {
        this.cartRepository = cartRepository;
    }

    public CartResponse getCart(UUID userId) {
        return CartResponse.of(cartRepository.findAll(userId));
    }

    /**
     * Add-or-increment semantics: adding a product already in the cart
     * sums the quantities (capped at 99) rather than erroring or
     * overwriting - the behavior every shopper expects from a "+ Add to
     * cart" button pressed twice.
     */
    public CartResponse addItem(UUID userId, AddItemRequest request) {
        CartItem existing = cartRepository.find(userId, request.productId());

        CartItem toStore = (existing == null)
                ? new CartItem(request.productId(), request.name(), request.price(),
                request.quantity(), Instant.now())
                : existing.withQuantity(Math.min(99, existing.quantity() + request.quantity()));

        cartRepository.put(userId, toStore);
        return getCart(userId);
    }

    public CartResponse updateQuantity(UUID userId, UUID productId, int quantity) {
        CartItem existing = cartRepository.find(userId, productId);
        if (existing == null) {
            throw new ResourceNotFoundException("Item not in cart: " + productId);
        }
        cartRepository.put(userId, existing.withQuantity(quantity));
        return getCart(userId);
    }

    public CartResponse removeItem(UUID userId, UUID productId) {
        cartRepository.remove(userId, productId);
        return getCart(userId);
    }

    /**
     * Called by the frontend after a successful checkout (order accepted).
     * Design note: order-service could instead publish OrderCompletedEvent
     * -> cart-service consumes and clears - fully decoupled, but the cart
     * would linger visibly for seconds after checkout (eventual
     * consistency where the user is staring right at it). A frontend-
     * driven explicit clear is the pragmatic answer; the event-driven
     * clear is the belt-and-suspenders addition for when the frontend
     * call is lost.
     */
    public void clearCart(UUID userId) {
        cartRepository.clear(userId);
    }
}