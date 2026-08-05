package com.easyshop.cart.service;

import com.easyshop.cart.dto.CartDtos.AddItemRequest;
import com.easyshop.cart.dto.CartDtos.CartItem;
import com.easyshop.cart.dto.CartDtos.CartResponse;
import com.easyshop.cart.repository.CartKey;
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
 * These methods take a CartKey rather than a UUID, so every operation works
 * against a guest cart exactly as it does against a session cart - the two
 * differ only in key-space and TTL (see CartProperties), not in behaviour.
 *
 * GUEST CARTS are fully live: GuestCartController serves the anonymous
 * key-space, CartProperties gives it a shorter TTL, and mergeGuestIntoSession()
 * below folds it into the user's cart at login.
 */
@Service
public class CartService {

    /** Matches the @Max(99) bound on AddItemRequest/UpdateQuantityRequest. */
    private static final int MAX_QUANTITY = 99;

    private final CartRepository cartRepository;

    public CartService(CartRepository cartRepository) {
        this.cartRepository = cartRepository;
    }

    public CartResponse getCart(CartKey cart) {
        return CartResponse.of(cartRepository.findAll(cart));
    }

    /**
     * Add-or-increment semantics: adding a product already in the cart
     * sums the quantities (capped at 99) rather than erroring or
     * overwriting - the behavior every shopper expects from a "+ Add to
     * cart" button pressed twice.
     */
    public CartResponse addItem(CartKey cart, AddItemRequest request) {
        CartItem existing = cartRepository.find(cart, request.productId());

        CartItem toStore = (existing == null)
                ? new CartItem(request.productId(), request.name(), request.price(),
                request.quantity(), Instant.now())
                : existing.withQuantity(Math.min(MAX_QUANTITY, existing.quantity() + request.quantity()));

        cartRepository.put(cart, toStore);
        return getCart(cart);
    }

    /**
     * Folds a guest cart into the signed-in user's cart, then discards the guest
     * key. Called explicitly by the client after login - the same
     * frontend-driven posture as clearCart(), and for the same reason: the
     * alternative (cart-service reacting to a login event) leaves the shopper
     * staring at a cart that is briefly missing what they just added as a guest.
     *
     * MERGE POLICY: quantities SUM on collision, capped at MAX_QUANTITY - the
     * same add-or-increment rule addItem() already applies, so "add 2 as a
     * guest, log in, add 2 more" behaves identically whether or not a login
     * happened in the middle. Nothing a shopper put in either cart disappears,
     * which is the property worth protecting; the alternatives (guest wins /
     * user wins) both silently discard one side.
     *
     * NOT ATOMIC, deliberately, and bounded on purpose: each guest item is
     * removed from the guest cart as soon as it has been written to the user
     * cart, so a crash mid-merge leaves at most the one in-flight item to be
     * counted twice on a retry, rather than re-summing the entire cart. Full
     * atomicity would need MULTI/EXEC around a read-modify-write; that is real
     * ceremony to protect a quantity that is already capped at 99, on the one
     * operation a user performs at most once per login.
     */
    public CartResponse mergeGuestIntoSession(CartKey.Guest guest, CartKey.Session session) {
        for (CartItem guestItem : cartRepository.findAll(guest)) {
            CartItem existing = cartRepository.find(session, guestItem.productId());

            // A brand-new product keeps the guest cart's addedAt, so the merged
            // cart still sorts by when the shopper actually chose each item.
            CartItem merged = (existing == null)
                    ? guestItem
                    : existing.withQuantity(
                            Math.min(MAX_QUANTITY, existing.quantity() + guestItem.quantity()));

            cartRepository.put(session, merged);
            cartRepository.remove(guest, guestItem.productId());
        }

        // Belt and braces: removing the last field already drops the key, but an
        // explicit clear guarantees the post-condition callers care about - the
        // guest cart is gone and cannot be merged a second time.
        cartRepository.clear(guest);
        return getCart(session);
    }

    public CartResponse updateQuantity(CartKey cart, UUID productId, int quantity) {
        CartItem existing = cartRepository.find(cart, productId);
        if (existing == null) {
            throw new ResourceNotFoundException("Item not in cart: " + productId);
        }
        cartRepository.put(cart, existing.withQuantity(quantity));
        return getCart(cart);
    }

    public CartResponse removeItem(CartKey cart, UUID productId) {
        cartRepository.remove(cart, productId);
        return getCart(cart);
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
    public void clearCart(CartKey cart) {
        cartRepository.clear(cart);
    }
}
