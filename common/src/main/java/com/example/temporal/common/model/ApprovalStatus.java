package com.example.temporal.common.model;

/**
 * Tracks the human-approval lifecycle within the order workflow.
 *
 * <p>Exposed via {@code @QueryMethod getApprovalStatus()} so external
 * systems (UI, REST) can poll the current state.
 */
public enum ApprovalStatus {

    /** Fraud check activity has not yet returned. */
    PENDING_FRAUD_CHECK,

    /** Fraud check returned NEEDS_REVIEW — workflow is paused, waiting for a human signal. */
    WAITING_FOR_APPROVAL,

    /** A human reviewer approved the order. */
    APPROVED,

    /** Either the fraud engine or a human reviewer rejected the order. */
    REJECTED,

    /** No human responded within the allowed timeout window. */
    TIMED_OUT,

    /** Fraud check auto-approved or workflow version is V0 (no fraud check). */
    NOT_REQUIRED
}

