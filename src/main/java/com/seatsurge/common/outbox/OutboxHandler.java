package com.seatsurge.common.outbox;

import tools.jackson.databind.JsonNode;

/**
 * Executes one type of outbox event. Delivery is at-least-once, so implementations must be idempotent
 * (for example by passing an idempotency key to the external system).
 */
public interface OutboxHandler {

    String eventType();

    void handle(JsonNode payload) throws Exception;
}
