package com.example.temporal.payment.activity;

import com.example.temporal.common.activity.PaymentActivity;
import com.example.temporal.common.model.PaymentRequest;
import com.example.temporal.common.model.PaymentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Payment activity implementation.
 *
 * <p>In a real system this would integrate with a payment gateway (Stripe, Braintree, etc.).
 * Here we simulate the charge with a stub that always succeeds unless the amount is zero/null.
 */
@Service
public class PaymentActivityImpl implements PaymentActivity {

    private static final Logger log = LoggerFactory.getLogger(PaymentActivityImpl.class);

    @Override
    public PaymentResult processPayment(PaymentRequest request) {
        log.info("[PAYMENT] Processing payment for orderId={}, customerId={}, amount={}",
                request.orderId(), request.customerId(), request.amount());

        if (request.amount() == null || request.amount().signum() <= 0) {
            log.warn("[PAYMENT] Invalid amount for orderId={}", request.orderId());
            return new PaymentResult(null, false, request.amount(), "Invalid payment amount");
        }

        String transactionId = "TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        log.info("[PAYMENT] Payment successful for orderId={}, transactionId={}", request.orderId(), transactionId);

        return new PaymentResult(transactionId, true, request.amount(), "Payment processed successfully");
    }

    @Override
    public void refundPayment(PaymentRequest request) {
        log.info("[PAYMENT][COMPENSATION] Refunding payment for orderId={}, customerId={}",
                request.orderId(), request.customerId());
        // In a real system: call payment gateway refund API
        log.info("[PAYMENT][COMPENSATION] Refund completed for orderId={}", request.orderId());
    }
}
