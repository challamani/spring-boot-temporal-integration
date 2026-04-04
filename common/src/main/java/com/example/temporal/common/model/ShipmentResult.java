package com.example.temporal.common.model;

/**
 * Result returned by the ShippingActivity.
 */
public record ShipmentResult(
        /** Carrier-assigned shipment identifier */
        String shipmentId,
        /** Publicly visible tracking number */
        String trackingNumber,
        /** true = shipment was created successfully */
        boolean success,
        String message) {
}
