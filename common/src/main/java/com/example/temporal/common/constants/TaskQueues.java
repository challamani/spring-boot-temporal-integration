package com.example.temporal.common.constants;

/**
 * Temporal Task Queue name constants shared across all services.
 */
public final class TaskQueues {

    private TaskQueues() {}

    public static final String ORDER_TASK_QUEUE     = "ORDER_TASK_QUEUE";
    public static final String PAYMENT_TASK_QUEUE   = "PAYMENT_TASK_QUEUE";
    public static final String INVENTORY_TASK_QUEUE = "INVENTORY_TASK_QUEUE";
    public static final String LOGISTICS_TASK_QUEUE = "LOGISTICS_TASK_QUEUE";
}

