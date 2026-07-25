package com.easyshop.payment.gateway;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stands in for a real PSP integration (Stripe, Adyen, etc.) - swap this
 * implementation for a real HTTP client without touching any caller, since
 * callers depend on the interface only (Dependency Inversion again).
 *
 * Demonstrates Resilience4j's two most commonly interview-tested annotations
 * together: @Retry handles transient failures (a blip that resolves itself
 * on a second attempt), @CircuitBreaker handles sustained failures (the
 * gateway is genuinely down - stop hammering it and fail fast instead).
 *
 * Why both, and why in this order: Retry wraps the innermost call and fires
 * first; CircuitBreaker wraps Retry and tracks the AGGREGATE failure rate
 * across many calls over time. If the gateway is having a bad minute, individual
 * Retry attempts might still occasionally succeed, but the CircuitBreaker
 * looks at the bigger picture and trips OPEN once the failure rate crosses
 * its configured threshold, protecting the rest of the system (and the
 * struggling downstream gateway) from a continued hammering.
 */
@Component
public class PaymentGatewayClient implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(PaymentGatewayClient.class);

    @Override
    @CircuitBreaker(name = "paymentGateway", fallbackMethod = "chargeFallback")
    @Retry(name = "paymentGateway")
    public GatewayResult charge(UUID orderId, BigDecimal amount, String currency, String pspIdempotencyKey) {
        // Simulated gateway call - replace with a real HTTP client (e.g. via
        // Spring Boot 4's new @HttpServiceClient, see Phase 2 notes) calling
        // Stripe's PaymentIntents API or equivalent. A real integration sends
        // pspIdempotencyKey as the Idempotency-Key header on that call - this
        // simulation has no state to dedupe against, so it's accepted but
        // unused here (logged so it's visible the plumbing is in place).
        log.info("Calling payment gateway for order {} amount {} {} (idempotencyKey={})",
                orderId, amount, currency, pspIdempotencyKey);

        simulateNetworkLatency();

        // Simulated 5% random failure rate to exercise the resilience config
        // during local testing - remove this in favor of real gateway responses.
        if (ThreadLocalRandom.current().nextInt(100) < 5) {
            throw new PaymentGatewayException("Simulated gateway timeout");
        }

        // Simulated decline rate (a legitimate business failure, NOT an
        // exception - a declined card is not the same as a broken gateway,
        // and should not trip the circuit breaker or trigger a retry).
        boolean declined = ThreadLocalRandom.current().nextInt(100) < 3;
        if (declined) {
            return GatewayResult.declined("Card declined by issuer");
        }

        return GatewayResult.success("gw_" + UUID.randomUUID());
    }

    @Override
    @CircuitBreaker(name = "paymentGateway", fallbackMethod = "refundFallback")
    @Retry(name = "paymentGateway")
    public GatewayResult refund(UUID originalTransactionId, String pspIdempotencyKey) {
        log.info("Calling payment gateway to refund transaction {} (idempotencyKey={})",
                originalTransactionId, pspIdempotencyKey);

        simulateNetworkLatency();

        if (ThreadLocalRandom.current().nextInt(100) < 5) {
            throw new PaymentGatewayException("Simulated gateway timeout");
        }

        // Unlike a charge, a refund against a real prior transaction has no
        // meaningful "declined" outcome in this simulation - it either goes
        // through or transiently fails (handled above/by the fallback).
        return GatewayResult.success("rf_" + UUID.randomUUID());
    }

    /**
     * Fallback invoked once the circuit breaker is OPEN (or Retry attempts
     * are exhausted on a genuinely failing call). Returning a clear FAILED
     * result here - rather than throwing further - lets the saga's existing
     * compensation logic (Phase 3) handle this exactly like a card decline,
     * without needing special-case error handling for "the gateway was down."
     */
    private GatewayResult chargeFallback(UUID orderId, BigDecimal amount, String currency,
                                         String pspIdempotencyKey, Throwable t) {
        log.error("Payment gateway circuit breaker fallback triggered for order {}: {}",
                orderId, t.getMessage());
        return GatewayResult.declined("Payment gateway temporarily unavailable");
    }

    private GatewayResult refundFallback(UUID originalTransactionId, String pspIdempotencyKey, Throwable t) {
        log.error("Payment gateway circuit breaker fallback triggered for refund of transaction {}: {}",
                originalTransactionId, t.getMessage());
        return GatewayResult.declined("Payment gateway temporarily unavailable");
    }

    private void simulateNetworkLatency() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(50, 200));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}