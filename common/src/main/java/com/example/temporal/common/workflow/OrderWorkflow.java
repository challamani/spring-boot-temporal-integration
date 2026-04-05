package com.example.temporal.common.workflow;

import com.example.temporal.common.model.ApprovalStatus;
import com.example.temporal.common.model.OrderRequest;
import com.example.temporal.common.model.OrderResult;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Temporal workflow contract for the order-processing saga.
 *
 * <p>Execution steps (in order):
 * <ol>
 *   <li>Reserve inventory   (InventoryActivity  → inventory-service)
 *   <li>Fraud check         (FraudCheckActivity → order-service)
 *   <li><b>Human approval</b> – if fraud check returns {@code NEEDS_REVIEW},
 *       the workflow pauses and waits for a {@link #approveOrder} signal
 *   <li>Process payment     (PaymentActivity    → payment-service)
 *   <li>Create shipment     (ShippingActivity   → logistics-service)
 * </ol>
 *
 * <p>On any step failure the workflow runs saga compensations in reverse order
 * before returning a FAILED result.
 *
 * <h3>Versioning</h3>
 * The fraud-check / human-approval step is gated behind
 * {@code Workflow.getVersion("FraudCheckStep", ...)} so that already-running
 * V0 workflows are not affected.
 */
@WorkflowInterface
public interface OrderWorkflow {

    @WorkflowMethod
    OrderResult processOrder(OrderRequest request);

    /**
     * Signal sent by a human reviewer to approve or reject the order.
     *
     * @param approved     {@code true} to approve, {@code false} to reject
     * @param reviewerNote free-text note from the reviewer
     */
    @SignalMethod
    void approveOrder(boolean approved, String reviewerNote);

    /**
     * Query the current approval status of the workflow.
     */
    @QueryMethod
    ApprovalStatus getApprovalStatus();
}

