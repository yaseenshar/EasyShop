package com.easyshop.payment.gateway;

/**
 * Represents a TRANSIENT/infrastructure failure talking to the gateway
 * (timeout, connection refused, 5xx) - distinct from a business decline
 * (insufficient funds, card expired), which is represented as a normal
 * GatewayResult.declined(...) return value, not an exception. This
 * distinction is exactly what lets Resilience4j's Retry/CircuitBreaker
 * annotations target real infrastructure problems without misfiring on
 * ordinary declined-card business outcomes.
 */
public class PaymentGatewayException extends RuntimeException {
    public PaymentGatewayException(String message) {
        super(message);
    }
}