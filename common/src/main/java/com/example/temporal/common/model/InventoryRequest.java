package com.example.temporal.common.model;

/**
 * Request payload sent to the InventoryActivity.
 */
public record InventoryRequest(
        String orderId,
        String productId,
        int quantity) {
}
