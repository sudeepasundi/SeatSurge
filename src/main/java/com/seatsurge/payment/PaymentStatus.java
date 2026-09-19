package com.seatsurge.payment;

public enum PaymentStatus {
    /** Order exists; Stripe session not created yet (or creation must be retried). */
    CREATED,
    /** Stripe Checkout session open. */
    PENDING,
    SUCCEEDED,
    EXPIRED,
    REFUNDED
}
