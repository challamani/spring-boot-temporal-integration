package com.example.temporal.common.model;

/**
 * Result returned by the FraudCheckActivity after automated pre-screening.
 *
 * <p>The {@code decision} field will be one of:
 * <ul>
 *   <li>{@code APPROVED}     – low risk, proceed automatically
 *   <li>{@code REJECTED}     – clearly fraudulent, fail the order immediately
 *   <li>{@code NEEDS_REVIEW} – uncertain, requires human-in-the-loop approval
 * </ul>
 */
public record FraudCheckResult(
        String orderId,
        // APPROVED | REJECTED | NEEDS_REVIEW
        String decision,
        // Risk score 0–100 computed by the fraud engine
        int riskScore,
        String reason) {

    public static final String APPROVED     = "APPROVED";
    public static final String REJECTED     = "REJECTED";
    public static final String NEEDS_REVIEW = "NEEDS_REVIEW";
}


