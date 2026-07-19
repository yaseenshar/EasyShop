package com.easyshop.payment.gateway;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentGateway {

    GatewayResult charge(UUID orderId, BigDecimal amount, String currency);

    record GatewayResult(boolean success, String gatewayReference, String failureReason) {
        public static GatewayResult success(String gatewayReference) {
            return new GatewayResult(true, gatewayReference, null);
        }
        public static GatewayResult declined(String reason) {
            return new GatewayResult(false, null, reason);
        }
    }
}