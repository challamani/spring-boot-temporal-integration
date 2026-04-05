package com.example.temporal.common.activity;

import com.example.temporal.common.model.FraudCheckRequest;
import com.example.temporal.common.model.FraudCheckResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Temporal activity interface for automated fraud pre-screening.
 *
 * <p>Implemented and registered by <b>order-service</b> on {@code ORDER_TASK_QUEUE}.
 * The activity evaluates order risk and returns one of three decisions:
 * {@code APPROVED}, {@code REJECTED}, or {@code NEEDS_REVIEW}.
 *
 * <p>When the decision is {@code NEEDS_REVIEW}, the workflow pauses for
 * human-in-the-loop approval via a Temporal Signal.
 */
@ActivityInterface
public interface FraudCheckActivity {

    /**
     * Performs an automated fraud risk assessment on the given order.
     *
     * @return {@link FraudCheckResult} with a decision and risk score
     */
    @ActivityMethod
    FraudCheckResult checkFraud(FraudCheckRequest request);
}

