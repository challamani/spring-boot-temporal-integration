package com.example.temporal.order.workflow;

import com.example.temporal.common.activity.InventoryActivity;
import com.example.temporal.common.activity.PaymentActivity;
import com.example.temporal.common.activity.ShippingActivity;
import com.example.temporal.common.constants.TaskQueues;
import com.example.temporal.common.model.*;
import com.example.temporal.common.workflow.OrderWorkflow;
import io.temporal.activity.ActivityOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;

import java.time.Duration;

/**
 * Saga-based implementation of the order-processing workflow.
 *
 * <h3>Happy path</h3>
 * <pre>
 *  Reserve Inventory  →  Process Payment  →  Create Shipment  →  SUCCESS
 * </pre>
 *
 * <h3>Compensation (reverse order on any failure)</h3>
 * <pre>
 *  Shipment fails   → Refund Payment   → Cancel Reservation → FAILED
 *  Payment fails    →                    Cancel Reservation → FAILED
 *  Inventory fails  →                                         FAILED
 * </pre>
 *
 * <p><b>Note:</b> Temporal instantiates workflow implementations itself; this class must NOT
 * be a Spring bean.
 */
public class OrderWorkflowImpl implements OrderWorkflow {

    // ── activity stubs – each routed to the owning service's task queue ──────

    private final InventoryActivity inventoryActivity = Workflow.newActivityStub(
            InventoryActivity.class,
            ActivityOptions.newBuilder()
                    .setTaskQueue(TaskQueues.INVENTORY_TASK_QUEUE)
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .build());

    private final PaymentActivity paymentActivity = Workflow.newActivityStub(
            PaymentActivity.class,
            ActivityOptions.newBuilder()
                    .setTaskQueue(TaskQueues.PAYMENT_TASK_QUEUE)
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .build());

    private final ShippingActivity shippingActivity = Workflow.newActivityStub(
            ShippingActivity.class,
            ActivityOptions.newBuilder()
                    .setTaskQueue(TaskQueues.LOGISTICS_TASK_QUEUE)
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .build());

    @Override
    public OrderResult processOrder(OrderRequest request) {

        // Saga: compensations run in LIFO order when compensate() is called
        Saga saga = new Saga(new Saga.Options.Builder()
                .setParallelCompensation(false)
                .build());

        try {
            // ── Step 1: Reserve Inventory ────────────────────────────────────
            InventoryRequest inventoryRequest = new InventoryRequest(
                    request.orderId(), request.productId(), request.quantity());

            InventoryResult inventoryResult = inventoryActivity.reserveInventory(inventoryRequest);

            if (!inventoryResult.success()) {
                return new OrderResult(request.orderId(), "FAILED",
                        "Inventory reservation failed: " + inventoryResult.message(),
                        null, null, null);
            }

            // Register compensation: cancel reservation if a later step fails
            saga.addCompensation(() -> inventoryActivity.cancelReservation(inventoryRequest));

            // ── Step 2: Process Payment ──────────────────────────────────────
            PaymentRequest paymentRequest = new PaymentRequest(
                    request.orderId(), request.customerId(),
                    request.amount(), inventoryResult.reservationId());

            PaymentResult paymentResult = paymentActivity.processPayment(paymentRequest);

            if (!paymentResult.success()) {
                saga.compensate();
                return new OrderResult(request.orderId(), "FAILED",
                        "Payment failed: " + paymentResult.message(),
                        inventoryResult, null, null);
            }

            // Register compensation: refund if shipment fails
            saga.addCompensation(() -> paymentActivity.refundPayment(paymentRequest));

            // ── Step 3: Create Shipment ──────────────────────────────────────
            ShipmentRequest shipmentRequest = new ShipmentRequest(
                    request.orderId(), request.customerId(), request.shippingAddress(),
                    inventoryResult.reservationId(), paymentResult.transactionId());

            ShipmentResult shipmentResult = shippingActivity.createShipment(shipmentRequest);

            if (!shipmentResult.success()) {
                saga.compensate();
                return new OrderResult(request.orderId(), "FAILED",
                        "Shipment creation failed: " + shipmentResult.message(),
                        inventoryResult, paymentResult, null);
            }

            // ── All steps succeeded ──────────────────────────────────────────
            return new OrderResult(request.orderId(), "SUCCESS",
                    "Order processed successfully",
                    inventoryResult, paymentResult, shipmentResult);

        } catch (ActivityFailure e) {
            saga.compensate();
            throw e;
        }
    }
}
