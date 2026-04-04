package com.example.temporal.payment.config;

import com.example.temporal.common.constants.TaskQueues;
import com.example.temporal.payment.activity.PaymentActivityImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Temporal wiring for payment-service.
 * Registers {@link PaymentActivityImpl} as a worker on {@code PAYMENT_TASK_QUEUE}.
 */
@Configuration
public class TemporalConfig {

    @Value("${temporal.service-url:localhost:7233}")
    private String temporalServiceUrl;

    @Value("${temporal.namespace:default}")
    private String namespace;

    @Autowired
    private PaymentActivityImpl paymentActivity;

    @Bean
    public WorkflowServiceStubs workflowServiceStubs() {
        return WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder()
                        .setTarget(temporalServiceUrl)
                        .build());
    }

    @Bean
    public WorkflowClient workflowClient(WorkflowServiceStubs stubs) {
        return WorkflowClient.newInstance(stubs,
                WorkflowClientOptions.newBuilder()
                        .setNamespace(namespace)
                        .build());
    }

    @Bean
    public WorkerFactory workerFactory(WorkflowClient client) {
        return WorkerFactory.newInstance(client);
    }

    @Bean
    public ApplicationRunner temporalWorkerRunner(WorkerFactory workerFactory) {
        return (ApplicationArguments args) -> {
            Worker worker = workerFactory.newWorker(TaskQueues.PAYMENT_TASK_QUEUE);
            worker.registerActivitiesImplementations(paymentActivity);
            workerFactory.start();
        };
    }
}

