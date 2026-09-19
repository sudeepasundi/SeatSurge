package com.seatsurge.payment;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.seatsurge.common.config.SeatSurgeProperties;
import com.seatsurge.common.exception.ApiException;
import com.seatsurge.common.exception.BadRequestException;
import com.seatsurge.order.OrderService;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.net.Webhook;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Stripe webhook processing:
 * <ol>
 *   <li>verify the Stripe-Signature (HMAC over the raw body, with replay tolerance)</li>
 *   <li>deduplicate on the Stripe event id: Stripe delivers at least once and may retry</li>
 *   <li>apply the state change in the same transaction as the dedupe record, so a crash in between
 *       makes Stripe retry instead of losing or double-applying the event</li>
 * </ol>
 * The JSON is read directly instead of through Stripe's typed models, which keeps the handler
 * independent of the account's API version.
 */
@Service
public class StripeWebhookService {

    public enum Outcome { PROCESSED, DUPLICATE, IGNORED }

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookService.class);
    private static final long SIGNATURE_TOLERANCE_SECONDS = 300;

    private final OrderService orderService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String webhookSecret;

    public StripeWebhookService(OrderService orderService, JdbcTemplate jdbc, ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager, Clock clock, SeatSurgeProperties properties) {
        this.orderService = orderService;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.webhookSecret = properties.stripe().webhookSecret();
    }

    public Outcome handle(String payload, String signatureHeader) {
        verifySignature(payload, signatureHeader);
        JsonNode event = parse(payload);
        String eventId = event.path("id").asString();
        String type = event.path("type").asString();
        JsonNode object = event.path("data").path("object");
        if (eventId.isBlank() || type.isBlank()) {
            throw new BadRequestException("INVALID_WEBHOOK", "Event id or type missing");
        }

        return tx.execute(status -> {
            int firstDelivery = jdbc.update("""
                    insert into processed_webhook_events (event_id, event_type) values (?, ?)
                    on conflict (event_id) do nothing
                    """, eventId, type);
            if (firstDelivery == 0) {
                log.info("Duplicate Stripe event {} ({}) ignored", eventId, type);
                return Outcome.DUPLICATE;
            }
            return dispatch(type, object);
        });
    }

    private Outcome dispatch(String type, JsonNode session) {
        switch (type) {
            case "checkout.session.completed" -> {
                // Card payments are "paid" here; delayed methods confirm later via async_payment_succeeded.
                if (!"paid".equals(session.path("payment_status").asString())) {
                    return Outcome.IGNORED;
                }
                orderService.fulfill(orderId(session), session.path("id").asString(),
                        session.path("payment_intent").asString(null));
                return Outcome.PROCESSED;
            }
            case "checkout.session.async_payment_succeeded" -> {
                orderService.fulfill(orderId(session), session.path("id").asString(),
                        session.path("payment_intent").asString(null));
                return Outcome.PROCESSED;
            }
            case "checkout.session.expired", "checkout.session.async_payment_failed" -> {
                orderService.expireCheckout(orderId(session));
                return Outcome.PROCESSED;
            }
            default -> {
                return Outcome.IGNORED;
            }
        }
    }

    private static Long orderId(JsonNode session) {
        String id = session.path("metadata").path("order_id").asString(null);
        if (id == null) {
            id = session.path("client_reference_id").asString(null);
        }
        if (id == null) {
            throw new BadRequestException("INVALID_WEBHOOK", "Checkout session has no order reference");
        }
        return Long.valueOf(id);
    }

    private void verifySignature(String payload, String signatureHeader) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WEBHOOKS_NOT_CONFIGURED",
                    "Webhook secret is not configured (set STRIPE_WEBHOOK_SECRET)");
        }
        if (signatureHeader == null) {
            throw new BadRequestException("INVALID_SIGNATURE", "Missing Stripe-Signature header");
        }
        try {
            Webhook.Signature.verifyHeader(payload, signatureHeader, webhookSecret, SIGNATURE_TOLERANCE_SECONDS, clock);
        } catch (SignatureVerificationException e) {
            throw new BadRequestException("INVALID_SIGNATURE", "Stripe signature verification failed");
        }
    }

    private JsonNode parse(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (JacksonException e) {
            throw new BadRequestException("INVALID_WEBHOOK", "Payload is not valid JSON");
        }
    }
}
