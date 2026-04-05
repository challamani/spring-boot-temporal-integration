package com.example.temporal.order.controller;

import com.example.temporal.common.constants.TaskQueues;
import com.example.temporal.common.model.ApprovalStatus;
import com.example.temporal.common.model.OrderRequest;
import com.example.temporal.common.model.OrderResult;
import com.example.temporal.common.workflow.OrderWorkflow;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * REST entry point for the order-processing workflow.
 *
 * <ul>
 *   <li>POST /orders                          – starts an {@link OrderWorkflow} execution asynchronously
 *   <li>GET  /orders/{workflowId}             – fetches the result of a completed workflow
 *   <li>GET  /orders/{workflowId}/approval-status – queries the current human-approval status
 *   <li>POST /orders/{workflowId}/approve     – sends a human approval/rejection signal
 * </ul>
 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final WorkflowClient workflowClient;

    public OrderController(WorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
    }

    /**
     * Place a new order asynchronously. Returns immediately with workflow identifiers.
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> placeOrder(@RequestBody OrderRequest request) {
        log.info("Received order request: orderId={}, customerId={}", request.orderId(), request.customerId());

        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setTaskQueue(TaskQueues.ORDER_TASK_QUEUE)
                // Idempotent: re-submitting the same orderId attaches to the running execution
                .setWorkflowId("order-" + request.orderId())
                .build();

        OrderWorkflow workflow = workflowClient.newWorkflowStub(OrderWorkflow.class, options);
        WorkflowExecution execution = WorkflowClient.start(workflow::processOrder, request);

        log.info("Order {} started async: workflowId={}, runId={}",
                request.orderId(), execution.getWorkflowId(), execution.getRunId());

        return ResponseEntity.accepted().body(Map.of(
                "workflowId", execution.getWorkflowId(),
                "runId", execution.getRunId(),
                "status", "STARTED"
        ));
    }

    /**
     * Retrieve the result of a workflow execution by its workflow ID.
     *
     * <p>Waits up to 30 seconds for the workflow to complete. Returns:
     * <ul>
     *   <li>200 + {@link OrderResult} — workflow completed (success or business failure)
     *   <li>202 — workflow is still running (try again later)
     *   <li>500 — unexpected workflow failure
     * </ul>
     *
     * @param timeout optional query param to override the default 30s wait (e.g. {@code ?timeout=10})
     */
    @GetMapping("/{workflowId}")
    public ResponseEntity<?> getOrderResult(
            @PathVariable String workflowId,
            @RequestParam(defaultValue = "30") int timeout) {

        WorkflowStub stub = workflowClient.newUntypedWorkflowStub(workflowId,
                Optional.empty(), Optional.empty());

        try {
            OrderResult result = stub.getResult(timeout, TimeUnit.SECONDS, OrderResult.class);
            return ResponseEntity.ok(result);

        } catch (TimeoutException e) {
            // Workflow is still running — tell the caller to poll again
            log.info("Workflow {} still running after {}s timeout", workflowId, timeout);
            return ResponseEntity.accepted().body(Map.of(
                    "workflowId", workflowId,
                    "status", "RUNNING",
                    "message", "Workflow still in progress, poll again later"
            ));

        } catch (WorkflowFailedException e) {
            // Workflow completed with an exception (e.g. unhandled ActivityFailure)
            log.error("Workflow {} failed: {}", workflowId, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "workflowId", workflowId,
                    "status", "FAILED",
                    "message", "Workflow execution failed: " + e.getMessage()
            ));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Human-in-the-Loop endpoints
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Query the current approval status of a running workflow.
     *
     * <p>Returns one of: {@code PENDING_FRAUD_CHECK}, {@code WAITING_FOR_APPROVAL},
     * {@code APPROVED}, {@code REJECTED}, {@code TIMED_OUT}, or {@code NOT_REQUIRED}.
     *
     * <p>Example:
     * <pre>GET /orders/order-123/approval-status</pre>
     */
    @GetMapping("/{workflowId}/approval-status")
    public ResponseEntity<Map<String, String>> getApprovalStatus(@PathVariable String workflowId) {
        log.info("Querying approval status for workflowId={}", workflowId);

        OrderWorkflow workflow = workflowClient.newWorkflowStub(OrderWorkflow.class, workflowId);
        ApprovalStatus status = workflow.getApprovalStatus();

        return ResponseEntity.ok(Map.of(
                "workflowId", workflowId,
                "approvalStatus", status.name()
        ));
    }

    /**
     * Send a human approval or rejection signal to a running workflow.
     *
     * <p>The workflow must be in {@code WAITING_FOR_APPROVAL} state for this to have effect.
     * The signal is durable – even if the worker is temporarily down, the signal will be
     * delivered when the worker reconnects.
     *
     * <p>Request body:
     * <pre>
     * {
     *   "approved": true,
     *   "reviewerNote": "Verified customer identity"
     * }
     * </pre>
     *
     * <p>Example:
     * <pre>POST /orders/order-123/approve</pre>
     */
    @PostMapping("/{workflowId}/approve")
    public ResponseEntity<Map<String, String>> approveOrder(
            @PathVariable String workflowId,
            @RequestBody ApprovalRequest approvalRequest) {

        log.info("Received approval signal for workflowId={}: approved={}, note='{}'",
                workflowId, approvalRequest.approved(), approvalRequest.reviewerNote());

        OrderWorkflow workflow = workflowClient.newWorkflowStub(OrderWorkflow.class, workflowId);
        workflow.approveOrder(approvalRequest.approved(), approvalRequest.reviewerNote());

        String action = approvalRequest.approved() ? "APPROVED" : "REJECTED";
        log.info("Approval signal sent to workflowId={}: {}", workflowId, action);

        return ResponseEntity.ok(Map.of(
                "workflowId", workflowId,
                "action", action,
                "message", "Signal delivered to workflow"
        ));
    }

    /**
     * Request body for the approval endpoint.
     */
    public record ApprovalRequest(boolean approved, String reviewerNote) {}
}
