package com.example.temporal.common.model;

import java.math.BigDecimal;

/**
 * Represents an incoming order request to start the workflow.
 */
public record OrderRequest(
        String orderId,
        String customerId,
        String productId,
        int quantity,
        BigDecimal amount,
        String shippingAddress) {
}
