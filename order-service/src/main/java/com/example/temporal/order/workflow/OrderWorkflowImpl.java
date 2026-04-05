package com.example.temporal.order.workflow;

import com.example.temporal.common.activity.FraudCheckActivity;
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
import org.slf4j.Logger;

import java.time.Duration;

/**
 * Saga-based implementation of the order-processing workflow.
 *
 * <p><b>V1 flow</b> (with fraud check and human-in-the-loop):
 * <pre>
 *   Inventory ─→ Fraud Check ─→ [Human Approval] ─→ Payment ─→ Shipment ─→ SUCCESS
 * </pre>
 *
 * <p><b>V0 flow</b> (legacy — no fraud check):
 * <pre>
 *   Inventory ─→ Payment ─→ Shipment ─→ SUCCESS
 * </pre>
 *
 * <p><b>Compensation</b> (reverse order on any failure):
 * <pre>
 *   … ─→ Refund Payment ─→ Cancel Reservation ─→ FAILED
 * </pre>
 *
 * <p><b>Note:</b> Temporal instantiates this class directly — it must NOT be a Spring bean.
 *
 * @see OrderWorkflow
 */
public class OrderWorkflowImpl implements OrderWorkflow {

    // ── Constants ───────────────────────────────────────────────────────────

    private static final Duration ACTIVITY_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration APPROVAL_TIMEOUT = Duration.ofHours(24);
    private static final String   VERSION_FRAUD_CHECK = "FraudCheckStep";

    // ── Workflow-scoped logger (deterministic, replay-safe) ─────────────────

    private static final Logger log = Workflow.getLogger(OrderWorkflowImpl.class);

    // ── Signal / Query state ────────────────────────────────────────────────

    private Boolean        approvalDecision = null;   // null = pending, true = approved, false = rejected
    private String         reviewerNote     = null;
    private ApprovalStatus approvalStatus   = ApprovalStatus.PENDING_FRAUD_CHECK;

    // ── Activity stubs (one per downstream service) ─────────────────────────

    private final InventoryActivity  inventoryActivity  = activityStub(InventoryActivity.class,  TaskQueues.INVENTORY_TASK_QUEUE);
    private final FraudCheckActivity fraudCheckActivity = activityStub(FraudCheckActivity.class, TaskQueues.ORDER_TASK_QUEUE);
    private final PaymentActivity    paymentActivity    = activityStub(PaymentActivity.class,    TaskQueues.PAYMENT_TASK_QUEUE);
    private final ShippingActivity   shippingActivity   = activityStub(ShippingActivity.class,   TaskQueues.LOGISTICS_TASK_QUEUE);

    // ════════════════════════════════════════════════════════════════════════
    //  Main workflow method
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public OrderResult processOrder(OrderRequest request) {

        Saga saga = new Saga(new Saga.Options.Builder()
                .setParallelCompensation(false)
                .build());

        try {
            // Step 1 — Reserve inventory
            InventoryResult inventory = reserveInventory(request, saga);
            if (!inventory.success()) {
                return failedResult(request, "Inventory reservation failed: " + inventory.message());
            }

            // Step 2 — Fraud check + optional human approval  (V1 only, version-gated)
            OrderResult fraudGate = performFraudCheckAndApproval(request, inventory, saga);
            if (fraudGate != null) {
                return fraudGate;  // rejected or timed-out — already compensated inside
            }

            // Step 3 — Process payment
            PaymentResult payment = processPayment(request, inventory, saga);
            if (!payment.success()) {
                return failedResult(request, "Payment failed: " + payment.message(),
                        inventory, null, null);
            }

            // Step 4 — Create shipment
            ShipmentResult shipment = createShipment(request, inventory, payment);
            if (!shipment.success()) {
                saga.compensate();
                return failedResult(request, "Shipment creation failed: " + shipment.message(),
                        inventory, payment, null);
            }

            // All steps succeeded
            return successResult(request, inventory, payment, shipment);

        } catch (ActivityFailure e) {
            saga.compensate();
            throw e;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Signal handler — receives the human reviewer's decision
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public void approveOrder(boolean approved, String reviewerNote) {
        this.approvalDecision = approved;
        this.reviewerNote     = reviewerNote;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Query handler — exposes the current approval status
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public ApprovalStatus getApprovalStatus() {
        return approvalStatus;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Private saga steps
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Step 1: Reserves inventory and registers a compensation to cancel it on rollback.
     */
    private InventoryResult reserveInventory(OrderRequest request, Saga saga) {
        InventoryRequest inventoryRequest = new InventoryRequest(
                request.orderId(), request.productId(), request.quantity());

        InventoryResult result = inventoryActivity.reserveInventory(inventoryRequest);

        if (result.success()) {
            saga.addCompensation(() -> inventoryActivity.cancelReservation(inventoryRequest));
            log.info("Inventory reserved: orderId={}, reservationId={}", request.orderId(), result.reservationId());
        } else {
            log.warn("Inventory reservation failed: orderId={}, reason={}", request.orderId(), result.message());
        }

        return result;
    }

    /**
     * Step 2 (V1 only): Runs fraud check and, if the result is {@code NEEDS_REVIEW},
     * pauses the workflow for human approval via a Temporal Signal.
     *
     * <p><b>Versioning:</b> Gated behind {@code Workflow.getVersion("FraudCheckStep", ...)}
     * so that already-running V0 workflows skip this step entirely.
     *
     * @return {@code null} if the workflow should continue to payment,
     *         or a terminal {@link OrderResult} if the order was rejected/timed-out.
     */
    private OrderResult performFraudCheckAndApproval(OrderRequest request,
                                                     InventoryResult inventory,
                                                     Saga saga) {
        /*
         * Workflow.getVersion() records a version marker in the event history.
         *   • V0 (DEFAULT_VERSION): replay of already-running workflows → skip fraud check
         *   • V1:                   new workflows → execute fraud check
         */
        int version = Workflow.getVersion(VERSION_FRAUD_CHECK, Workflow.DEFAULT_VERSION, 1);

        if (version < 1) {
            approvalStatus = ApprovalStatus.NOT_REQUIRED;
            return null;   // V0 path — proceed directly to payment
        }

        // --- Run automated fraud check activity ---
        FraudCheckRequest fraudRequest = new FraudCheckRequest(
                request.orderId(), request.customerId(),
                request.amount(), request.shippingAddress());

        FraudCheckResult fraudResult = fraudCheckActivity.checkFraud(fraudRequest);
        log.info("Fraud check: orderId={}, decision={}, riskScore={}",
                request.orderId(), fraudResult.decision(), fraudResult.riskScore());

        return switch (fraudResult.decision()) {

            case FraudCheckResult.REJECTED -> {
                approvalStatus = ApprovalStatus.REJECTED;
                saga.compensate();
                yield failedResult(request,
                        "Fraud check rejected: " + fraudResult.reason(),
                        inventory, null, null);
            }

            case FraudCheckResult.NEEDS_REVIEW ->
                    waitForHumanApproval(request, inventory, saga, fraudResult.riskScore());

            default -> {
                // APPROVED — proceed automatically
                approvalStatus = ApprovalStatus.NOT_REQUIRED;
                yield null;
            }
        };
    }

    /**
     * Human-in-the-Loop: pauses the workflow until a reviewer sends an
     * {@link #approveOrder} signal or the timeout expires.
     *
     * <p>The pause is <b>durable</b> — the workflow survives server restarts,
     * worker downtime, and Temporal cluster failovers.
     *
     * @return {@code null} if approved (continue to payment),
     *         or a terminal {@link OrderResult} if rejected / timed-out.
     */
    private OrderResult waitForHumanApproval(OrderRequest request,
                                             InventoryResult inventory,
                                             Saga saga,
                                             int riskScore) {
        approvalStatus = ApprovalStatus.WAITING_FOR_APPROVAL;
        log.info("Awaiting human approval: orderId={}, riskScore={}, timeout={}",
                request.orderId(), riskScore, APPROVAL_TIMEOUT);

        // Durable wait — blocks until signal arrives OR timeout expires
        boolean signalReceived = Workflow.await(APPROVAL_TIMEOUT, () -> approvalDecision != null);

        // Timeout — no human responded
        if (!signalReceived) {
            approvalStatus = ApprovalStatus.TIMED_OUT;
            log.warn("Approval timed out: orderId={}", request.orderId());
            saga.compensate();
            return failedResult(request,
                    "Human approval timed out after " + APPROVAL_TIMEOUT,
                    inventory, null, null);
        }

        // Rejected by reviewer
        if (Boolean.FALSE.equals(approvalDecision)) {
            approvalStatus = ApprovalStatus.REJECTED;
            log.info("Rejected by reviewer: orderId={}, note={}", request.orderId(), reviewerNote);
            saga.compensate();
            return failedResult(request,
                    "Rejected by reviewer: " + reviewerNote,
                    inventory, null, null);
        }

        // Approved by reviewer — continue to payment
        approvalStatus = ApprovalStatus.APPROVED;
        log.info("Approved by reviewer: orderId={}, note={}", request.orderId(), reviewerNote);
        return null;
    }

    /**
     * Step 3: Charges the customer and registers a refund compensation.
     */
    private PaymentResult processPayment(OrderRequest request,
                                         InventoryResult inventory,
                                         Saga saga) {
        PaymentRequest paymentRequest = new PaymentRequest(
                request.orderId(), request.customerId(),
                request.amount(), inventory.reservationId());

        PaymentResult result = paymentActivity.processPayment(paymentRequest);

        if (result.success()) {
            saga.addCompensation(() -> paymentActivity.refundPayment(paymentRequest));
            log.info("Payment processed: orderId={}, txnId={}", request.orderId(), result.transactionId());
        } else {
            log.warn("Payment failed: orderId={}, reason={}", request.orderId(), result.message());
            saga.compensate();
        }

        return result;
    }

    /**
     * Step 4: Creates a shipment record with the carrier.
     */
    private ShipmentResult createShipment(OrderRequest request,
                                          InventoryResult inventory,
                                          PaymentResult payment) {
        ShipmentRequest shipmentRequest = new ShipmentRequest(
                request.orderId(), request.customerId(), request.shippingAddress(),
                inventory.reservationId(), payment.transactionId());

        ShipmentResult result = shippingActivity.createShipment(shipmentRequest);

        if (result.success()) {
            log.info("Shipment created: orderId={}, trackingNumber={}", request.orderId(), result.trackingNumber());
        } else {
            log.warn("Shipment failed: orderId={}, reason={}", request.orderId(), result.message());
        }

        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Result helpers
    // ════════════════════════════════════════════════════════════════════════

    private static OrderResult failedResult(OrderRequest request, String reason) {
        return new OrderResult(request.orderId(), "FAILED", reason, null, null, null);
    }

    private static OrderResult failedResult(OrderRequest request, String reason,
                                            InventoryResult inv, PaymentResult pay, ShipmentResult ship) {
        return new OrderResult(request.orderId(), "FAILED", reason, inv, pay, ship);
    }

    private static OrderResult successResult(OrderRequest request,
                                             InventoryResult inv, PaymentResult pay, ShipmentResult ship) {
        return new OrderResult(request.orderId(), "SUCCESS", "Order processed successfully", inv, pay, ship);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Activity stub factory
    // ════════════════════════════════════════════════════════════════════════

    private static <T> T activityStub(Class<T> activityClass, String taskQueue) {
        return Workflow.newActivityStub(activityClass,
                ActivityOptions.newBuilder()
                        .setTaskQueue(taskQueue)
                        .setStartToCloseTimeout(ACTIVITY_TIMEOUT)
                        .build());
    }
}
