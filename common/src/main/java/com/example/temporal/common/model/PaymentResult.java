package com.example.temporal.common.model;

import java.math.BigDecimal;

/**
 * Result returned by the PaymentActivity.
 */
public record PaymentResult(
        /** Unique payment / transaction identifier from the payment provider */
        String transactionId,
        /** true = charge succeeded */
        boolean success,
        BigDecimal amount,
        String message) {
}
