package com.seatsurge.payment;

import java.time.Instant;
import java.util.List;

/** Port to the payment provider (Stripe in production, a stub in tests). */
public interface PaymentGateway {

    CheckoutSession createCheckoutSession(CheckoutSessionRequest request);

    /** Refunds a captured payment in full. Must be idempotent for the same key. */
    void refund(String paymentIntentId, String idempotencyKey);

    record CheckoutSessionRequest(Long orderId, Long holdId, String customerEmail, String currency,
            List<LineItem> lineItems, Instant expiresAt, String idempotencyKey) {
    }

    record LineItem(String name, long amountCents) {
    }

    record CheckoutSession(String id, String url) {
    }
}
