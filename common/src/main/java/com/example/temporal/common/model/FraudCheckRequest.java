package com.example.temporal.common.model;

import java.math.BigDecimal;

/**
 * Request payload sent to the FraudCheckActivity for automated pre-screening.
 */
public record FraudCheckRequest(
        String orderId,
        String customerId,
        BigDecimal amount,
        String shippingAddress) {
}

