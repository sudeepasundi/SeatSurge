package com.seatsurge.payment;

import org.springframework.stereotype.Component;

import com.seatsurge.common.outbox.OutboxHandler;
import com.seatsurge.order.OrderService;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

/** Executes queued refunds. Safe to retry: Stripe deduplicates on the per-order idempotency key. */
@Component
@RequiredArgsConstructor
public class RefundHandler implements OutboxHandler {

    private final PaymentGateway paymentGateway;
    private final OrderService orderService;

    @Override
    public String eventType() {
        return OrderService.REFUND_REQUESTED;
    }

    @Override
    public void handle(JsonNode payload) {
        long orderId = payload.path("orderId").asLong();
        paymentGateway.refund(payload.path("paymentIntentId").asString(), "refund-order-" + orderId);
        orderService.markRefunded(orderId);
    }
}
