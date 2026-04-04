package com.example.temporal.common.model;

/**
 * Result returned by the InventoryActivity after a reserve or cancel operation.
 */
public record InventoryResult(
        /** Opaque ID that uniquely identifies the stock reservation */
        String reservationId,
        /** true = reservation succeeded; false = insufficient stock */
        boolean success,
        String message) {
}
