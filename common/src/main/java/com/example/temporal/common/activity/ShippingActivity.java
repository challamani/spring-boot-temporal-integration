package com.example.temporal.common.activity;

import com.example.temporal.common.model.ShipmentRequest;
import com.example.temporal.common.model.ShipmentResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Temporal activity interface for shipment / logistics operations.
 * Implemented and registered by <b>logistics-service</b> on {@code LOGISTICS_TASK_QUEUE}.
 */
@ActivityInterface
public interface ShippingActivity {

    /**
     * Creates a shipment record and assigns a tracking number.
     *
     * @return {@link ShipmentResult} with {@code success=true} and a {@code trackingNumber}
     *         on success, or {@code success=false} with a reason message on failure.
     */
    @ActivityMethod
    ShipmentResult createShipment(ShipmentRequest request);
}

