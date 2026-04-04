package com.example.temporal.common.model;

/**
 * Request payload sent to the ShippingActivity.
 */
public record ShipmentRequest(
        String orderId,
        String customerId,
        String shippingAddress,
        /** Correlation IDs from previous saga steps */
        String reservationId,
        String transactionId) {
}
