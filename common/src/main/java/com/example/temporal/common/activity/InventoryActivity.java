package com.example.temporal.common.activity;

import com.example.temporal.common.model.InventoryRequest;
import com.example.temporal.common.model.InventoryResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Temporal activity interface for inventory operations.
 * Implemented and registered by <b>inventory-service</b> on {@code INVENTORY_TASK_QUEUE}.
 */
@ActivityInterface
public interface InventoryActivity {

    /**
     * Reserves stock for the given order.
     *
     * @return {@link InventoryResult} with {@code success=true} and a {@code reservationId}
     *         if stock was available, or {@code success=false} otherwise.
     */
    @ActivityMethod
    InventoryResult reserveInventory(InventoryRequest request);

    /**
     * Saga compensation: releases a previously reserved stock allocation.
     * Called when a downstream step (payment or shipment) fails.
     */
    @ActivityMethod
    void cancelReservation(InventoryRequest request);
}

