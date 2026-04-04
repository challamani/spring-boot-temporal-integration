package com.example.temporal.order.controller;

import com.example.temporal.common.constants.TaskQueues;
import com.example.temporal.common.model.OrderRequest;
import com.example.temporal.common.model.OrderResult;
import com.example.temporal.common.workflow.OrderWorkflow;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST entry point for the order-processing workflow.
 *
 * <p>POST /orders – starts an {@link OrderWorkflow} execution asynchronously and returns
 * workflow identifiers ({@code workflowId}, {@code runId}) immediately.
 *
 * <p>GET  /orders/{workflowId} – fetches the result of an already-completed workflow.
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
     * Retrieve the result of a completed workflow execution by its workflow ID.
     */
    @GetMapping("/{workflowId}")
    public ResponseEntity<OrderResult> getOrderResult(@PathVariable String workflowId) {
        OrderResult result = workflowClient
                .newUntypedWorkflowStub(workflowId, null, null)
                .getResult(OrderResult.class);
        return ResponseEntity.ok(result);
    }
}
