package com.example.temporal.order.config;

import com.example.temporal.common.constants.TaskQueues;
import com.example.temporal.order.workflow.OrderWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Temporal wiring for order-service.
 *
 * <ul>
 *   <li>Connects to the Temporal frontend gRPC endpoint.
 *   <li>Creates a {@link WorkflowClient} used by the REST layer to start workflow executions.
 *   <li>Registers {@link OrderWorkflowImpl} as a workflow worker on {@code ORDER_TASK_QUEUE}.
 * </ul>
 */
@Configuration
public class TemporalConfig {

    @Value("${temporal.service-url:localhost:7233}")
    private String temporalServiceUrl;

    @Value("${temporal.namespace:default}")
    private String namespace;

    @Bean
    public WorkflowServiceStubs workflowServiceStubs() {
        WorkflowServiceStubsOptions options = WorkflowServiceStubsOptions.newBuilder()
                .setTarget(temporalServiceUrl)
                .build();
        return WorkflowServiceStubs.newServiceStubs(options);
    }

    @Bean
    public WorkflowClient workflowClient(WorkflowServiceStubs stubs) {
        WorkflowClientOptions clientOptions = WorkflowClientOptions.newBuilder()
                .setNamespace(namespace)
                .build();
        return WorkflowClient.newInstance(stubs, clientOptions);
    }

    @Bean
    public WorkerFactory workerFactory(WorkflowClient workflowClient) {
        return WorkerFactory.newInstance(workflowClient);
    }

    /**
     * Starts the Temporal worker once the Spring context is fully initialized.
     * The worker picks up {@code ORDER_TASK_QUEUE} tasks and executes {@link OrderWorkflowImpl}.
     */
    @Bean
    public ApplicationRunner temporalWorkerRunner(WorkerFactory workerFactory) {
        return (ApplicationArguments args) -> {
            Worker worker = workerFactory.newWorker(TaskQueues.ORDER_TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);
            workerFactory.start();
        };
    }
}

