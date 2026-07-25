// ============================================================================
// review-service — PurchaseVerifier with a THREAD POOL BULKHEAD.
//
// PURPOSE OF THE BULKHEAD: isolate review-service's Tomcat request threads from
// a slow order-service. Without it, if order-service hangs, every in-flight
// "does this user have a verified purchase?" call ties up a web thread; enough
// of them and review-service stops serving EVERYTHING — including plain review
// reads that never touch order-service. The bulkhead confines the damage to a
// small, dedicated pool. That containment IS the point (verify step: an
// unrelated endpoint stays fast while the bulkhead is saturated).
//
// THREE THINGS THIS REFACTOR FORCES (all verified, not assumed):
//
// 1. RETURN TYPE MUST BE CompletableFuture. @Bulkhead(type = THREADPOOL) runs
//    the body on a separate pool thread and hands back a future. A plain
//    boolean return does not isolate correctly. This ripples to the caller.
//
// 2. THREADLOCAL CONTEXT IS LOST on the pool thread — MDC (trace ids),
//    SecurityContext, request scope. Here that is ACCEPTABLE for auth because
//    the call uses review-service's OWN M2M client-credentials token (§8.2),
//    not the incoming user's SecurityContext. But MDC/tracing IS lost, so we
//    register an MDC ContextPropagator (see MdcContextPropagator.java) for the
//    §8.4 observability work. Known R4j quirk: MDC can be cleared before a
//    fallback/retry runs on the pool — which is one reason we do NOT stack
//    @Retry on this method (see below).
//
// 3. STILL A SEPARATE BEAN (§6.1). The self-invocation trap is unchanged:
//    @Bulkhead/@TimeLimiter/@CircuitBreaker only fire when the call crosses a
//    bean boundary. PurchaseVerifier stays its own bean; the review flow calls
//    into it.
//
// DELIBERATELY NO @Retry HERE: a transient blip on a Verified-Purchase badge
// lookup is not worth retrying — the §4.12 fallback already degrades it to
// UNVERIFIED, which is the correct, cheap answer. Adding retry would (a) hit
// the MDC-cleared-before-retry quirk on the thread-pool path and (b) multiply
// load on an already-struggling order-service. Retry belongs on the inventory
// optimistic-lock path, not on a best-effort badge.
// ============================================================================

package com.easyshop.review.service; // align with your package layout

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.easyshop.review.client.OrderPurchaseClient;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import org.springframework.stereotype.Component;

@Component
public class PurchaseVerifier {

    private final OrderPurchaseClient orderPurchaseClient;

    public PurchaseVerifier(OrderPurchaseClient orderPurchaseClient) {
        this.orderPurchaseClient = orderPurchaseClient;
    }

    /**
     * Instance name "orderInternal" ties together the circuitbreaker, timelimiter
     * and thread-pool-bulkhead configs in application.yml. Keep them consistent
     * or each unconfigured aspect silently falls back to defaults (e.g.
     * minimumNumberOfCalls: 100 — never opens in dev).
     *
     * Annotation order note: with type = THREADPOOL the bulkhead switches the
     * execution to async/CompletableFuture, so it effectively sits OUTERMOST and
     * TimeLimiter/CircuitBreaker operate on the returned future. The observable
     * contract is what the verify script asserts.
     */
    @Bulkhead(name = "orderInternal", type = Bulkhead.Type.THREADPOOL,
            fallbackMethod = "unverifiedFallback")
    @TimeLimiter(name = "orderInternal")
    @CircuitBreaker(name = "orderInternal")
    public CompletableFuture<Boolean> hasVerifiedPurchase(UUID userId, UUID productId) {
        // Runs on the bulkhead pool thread. Uses the M2M token (order-client
        // interceptor), so no user SecurityContext is needed here.
        boolean purchased = orderPurchaseClient.hasPurchased(userId, productId).purchased();
        return CompletableFuture.completedFuture(purchased);
    }

    /**
     * §4.12 degrade-the-feature-not-the-function: any failure — circuit OPEN
     * (CallNotPermittedException), timeout, or BulkheadFullException (pool +
     * queue saturated) — resolves to "not verified", never an error to the
     * caller. The review still posts/renders; it just lacks the badge.
     *
     * The fallback signature adds a Throwable to the original params. Keep the
     * return type CompletableFuture to match.
     */
    @SuppressWarnings("unused")
    private CompletableFuture<Boolean> unverifiedFallback(UUID userId, UUID productId, Throwable t) {
        // Log at DEBUG/INFO with the cause type so a saturated bulkhead vs an
        // open breaker vs a timeout are distinguishable (§6.3 — read the finer
        // channel). Do NOT log at ERROR: this is expected degradation.
        return CompletableFuture.completedFuture(false);
    }
}