package com.example.temporal.logistics.activity;

import com.example.temporal.common.activity.ShippingActivity;
import com.example.temporal.common.model.ShipmentRequest;
import com.example.temporal.common.model.ShipmentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Shipping activity implementation.
 *
 * <p>In a real system this would call a carrier API (FedEx, UPS, DHL, etc.).
 * Here we simulate shipment creation with a stub that always succeeds.
 */
@Service
public class ShippingActivityImpl implements ShippingActivity {

    private static final Logger log = LoggerFactory.getLogger(ShippingActivityImpl.class);

    @Override
    public ShipmentResult createShipment(ShipmentRequest request) {
        log.info("[LOGISTICS] Creating shipment for orderId={}, address='{}', reservationId={}, transactionId={}",
                request.orderId(), request.shippingAddress(),
                request.reservationId(), request.transactionId());

        if (request.shippingAddress() == null || request.shippingAddress().isBlank()) {
            log.warn("[LOGISTICS] Missing shipping address for orderId={}", request.orderId());
            return new ShipmentResult(null, null, false, "Shipping address is required");
        }

        String shipmentId     = "SHIP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String trackingNumber = "TRK-"  + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();

        log.info("[LOGISTICS] Shipment created: shipmentId={}, trackingNumber={}", shipmentId, trackingNumber);

        return new ShipmentResult(shipmentId, trackingNumber, true, "Shipment created successfully");
    }
}
