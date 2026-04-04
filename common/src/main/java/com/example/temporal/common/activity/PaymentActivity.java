package com.example.temporal.common.activity;

import com.example.temporal.common.model.PaymentRequest;
import com.example.temporal.common.model.PaymentResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Temporal activity interface for payment operations.
 * Implemented and registered by <b>payment-service</b> on {@code PAYMENT_TASK_QUEUE}.
 */
@ActivityInterface
public interface PaymentActivity {

    /**
     * Charges the customer for the order.
     *
     * @return {@link PaymentResult} with {@code success=true} and a {@code transactionId}
     *         on success, or {@code success=false} with a reason message on failure.
     */
    @ActivityMethod
    PaymentResult processPayment(PaymentRequest request);

    /**
     * Saga compensation: issues a full refund for the given order.
     * Called when shipment creation fails after a successful charge.
     */
    @ActivityMethod
    void refundPayment(PaymentRequest request);
}

