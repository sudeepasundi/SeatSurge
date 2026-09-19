package com.seatsurge.order;

public enum OrderStatus {
    /** Checkout session open, waiting for Stripe. */
    PENDING,
    /** Payment confirmed by webhook; tickets issued. */
    PAID,
    /** Checkout abandoned or expired; nothing was charged. */
    CANCELLED,
    /** Money was taken but the seats could not be delivered (e.g. payment after hold expiry); refund queued. */
    REFUND_PENDING,
    REFUNDED
}
