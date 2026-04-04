package com.example.temporal.common.model;

import java.math.BigDecimal;

/**
 * Request payload sent to the PaymentActivity.
 */
public record PaymentRequest(
        String orderId,
        String customerId,
        BigDecimal amount,
        /** Reservation ID from the inventory step, used for correlation */
        String reservationId) {
}
