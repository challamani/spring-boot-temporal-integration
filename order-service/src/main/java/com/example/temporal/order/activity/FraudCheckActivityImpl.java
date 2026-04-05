package com.example.temporal.order.activity;

import com.example.temporal.common.activity.FraudCheckActivity;
import com.example.temporal.common.model.FraudCheckRequest;
import com.example.temporal.common.model.FraudCheckResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

/**
 * Fraud-check activity implementation.
 *
 * <p>Uses simple rule-based heuristics for demonstration:
 * <ul>
 *   <li>Amount &gt; $1000  → {@code NEEDS_REVIEW} (human must approve)
 *   <li>Amount &gt; $5000  → {@code REJECTED} (auto-decline)
 *   <li>Otherwise          → {@code APPROVED} (auto-approve)
 * </ul>
 *
 * <p>Replace with a real ML-based fraud engine or third-party API (Sift, Riskified, etc.)
 * in production.
 *
 * <p><b>Note:</b> This is NOT a Spring bean – Temporal instantiates activity implementations
 * directly via the worker.  It is registered in {@code TemporalConfig}.
 */
public class FraudCheckActivityImpl implements FraudCheckActivity {

    private static final Logger log = LoggerFactory.getLogger(FraudCheckActivityImpl.class);

    /** Orders above this amount require human review */
    private static final BigDecimal REVIEW_THRESHOLD = new BigDecimal("1000");

    /** Orders above this amount are auto-rejected */
    private static final BigDecimal REJECT_THRESHOLD = new BigDecimal("5000");

    @Override
    public FraudCheckResult checkFraud(FraudCheckRequest request) {
        log.info("[FRAUD-CHECK] Evaluating orderId={}, customerId={}, amount={}",
                request.orderId(), request.customerId(), request.amount());

        if (request.amount() == null) {
            log.warn("[FRAUD-CHECK] Null amount for orderId={}", request.orderId());
            return new FraudCheckResult(request.orderId(), FraudCheckResult.REJECTED, 100,
                    "Missing order amount");
        }

        int riskScore = computeRiskScore(request);

        if (request.amount().compareTo(REJECT_THRESHOLD) > 0) {
            log.warn("[FRAUD-CHECK] Auto-REJECTED orderId={} (amount={}, riskScore={})",
                    request.orderId(), request.amount(), riskScore);
            return new FraudCheckResult(request.orderId(), FraudCheckResult.REJECTED, riskScore,
                    "Order amount exceeds automatic approval limit");
        }

        if (request.amount().compareTo(REVIEW_THRESHOLD) > 0) {
            log.info("[FRAUD-CHECK] NEEDS_REVIEW orderId={} (amount={}, riskScore={})",
                    request.orderId(), request.amount(), riskScore);
            return new FraudCheckResult(request.orderId(), FraudCheckResult.NEEDS_REVIEW, riskScore,
                    "Order amount requires manual review");
        }

        log.info("[FRAUD-CHECK] Auto-APPROVED orderId={} (amount={}, riskScore={})",
                request.orderId(), request.amount(), riskScore);
        return new FraudCheckResult(request.orderId(), FraudCheckResult.APPROVED, riskScore,
                "Order passed automated fraud check");
    }

    /**
     * Very simple risk-score heuristic (0 = low risk, 100 = high risk).
     * In production this would call an ML model or external scoring API.
     */
    private int computeRiskScore(FraudCheckRequest request) {
        // Linear mapping: $0 → score 0, $5000+ → score 100
        double raw = request.amount().doubleValue() / REJECT_THRESHOLD.doubleValue() * 100;
        return (int) Math.min(100, Math.max(0, raw));
    }
}

