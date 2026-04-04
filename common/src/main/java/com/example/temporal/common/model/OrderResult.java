package com.example.temporal.common.model;

/**
 * Final result returned by the OrderWorkflow after all saga steps complete (or fail).
 */
public record OrderResult(
        String orderId,
        /** "SUCCESS" or "FAILED" */
        String status,
        String message,
        InventoryResult inventoryResult,
        PaymentResult paymentResult,
        ShipmentResult shipmentResult) {
}
