package com.example.temporal.common.workflow;

import com.example.temporal.common.model.OrderRequest;
import com.example.temporal.common.model.OrderResult;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Temporal workflow contract for the order-processing saga.
 *
 * <p>Execution steps (in order):
 * <ol>
 *   <li>Reserve inventory  (InventoryActivity → inventory-service)
 *   <li>Process payment    (PaymentActivity  → payment-service)
 *   <li>Create shipment    (ShippingActivity → logistics-service)
 * </ol>
 *
 * On any step failure the workflow runs saga compensations in reverse order before returning
 * a FAILED result.
 */
@WorkflowInterface
public interface OrderWorkflow {

    @WorkflowMethod
    OrderResult processOrder(OrderRequest request);
}

