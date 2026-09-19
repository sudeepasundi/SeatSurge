package com.seatsurge.payment;

/** Projection: a paid order and the Stripe payment to refund. */
public record PaidOrderRef(Long orderId, String paymentIntentId) {
}
