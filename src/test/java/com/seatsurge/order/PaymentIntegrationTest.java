package com.seatsurge.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import com.seatsurge.common.outbox.OutboxPublisher;
import com.seatsurge.support.IntegrationTest;
import com.seatsurge.support.TestStubsConfiguration.FakePaymentGateway;
import com.seatsurge.support.TestStubsConfiguration.RecordingMailer;

class PaymentIntegrationTest extends IntegrationTest {

    @Autowired
    private FakePaymentGateway stripe;

    @Autowired
    private RecordingMailer mailer;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private JdbcTemplate jdbc;

    private TestEvent event;
    private String fan;
    private String fanEmail;

    @BeforeEach
    void setUp() throws Exception {
        event = createPublishedEvent(tokenFor("ORGANIZER"), 10, 4, true);
        fan = tokenFor("FAN");
        fanEmail = read(getAs(fan, "/api/v1/users/me"), "$.email");
    }

    @Test
    void checkoutIsIdempotentAndPaidWebhookIssuesTicketsExactlyOnce() throws Exception {
        long holdId = hold(fan, seat(0), seat(1));

        checkout(fan, holdId, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        String key = UUID.randomUUID().toString();
        ResultActions first = checkout(fan, holdId, key)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amountCents").value(10000))
                .andExpect(jsonPath("$.checkoutUrl", startsWith("https://checkout.stripe.test/")));
        long orderId = ((Number) read(first, "$.orderId")).longValue();
        String firstBody = first.andReturn().getResponse().getContentAsString();

        // Network retry with the same key: stored response replayed, nothing re-executed
        String replayBody = checkout(fan, holdId, key)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();
        assertThat(replayBody).isEqualTo(firstBody);

        // New key, same hold: same order (one order per hold), still only one Stripe session
        checkout(fan, holdId, UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(orderId));
        assertThat(stripe.sessionsCreatedFor(orderId)).isEqualTo(1);

        // Same key for a different request is a client bug
        checkout(fan, 999_999L, key)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        // Checkout extends the hold to outlive the 30-minute Stripe session
        getAs(fan, "/api/v1/holds/{id}", holdId).andExpect(jsonPath("$.secondsRemaining", greaterThan(1800)));

        // Stripe confirms payment
        String paid = stripeEvent("checkout.session.completed", orderId, "paid", "pi_" + orderId);
        webhook(paid).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROCESSED"));

        getAs(fan, "/api/v1/orders/{id}", orderId)
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.tickets", hasSize(2)))
                .andExpect(jsonPath("$.tickets[0].status").value("VALID"));
        getAs(fan, "/api/v1/holds/{id}", holdId).andExpect(jsonPath("$.status").value("CONVERTED"));
        getAs(null, "/api/v1/events/{id}/seats", event.eventId())
                .andExpect(jsonPath("$.availableSeats").value(8))
                .andExpect(jsonPath("$.sections[0].rows[0].seats[0].status").value("SOLD"));

        // Stripe redelivers the same event, then a second "completed" event: no double fulfillment
        webhook(paid).andExpect(jsonPath("$.status").value("DUPLICATE"));
        webhook(stripeEvent("checkout.session.completed", orderId, "paid", "pi_" + orderId))
                .andExpect(jsonPath("$.status").value("PROCESSED"));
        assertThat(ticketCount(orderId)).isEqualTo(2);

        // Confirmation email goes out through the outbox
        outboxPublisher.processBatch();
        assertThat(mailer.sentTo(fanEmail)).singleElement().satisfies(mail -> {
            assertThat(mail.subject()).isEqualTo("Your tickets for Flash Sale Night");
            assertThat(mail.body()).contains("Section Main, Row A, Seat 1").contains("100.00 USD");
        });

        checkout(fan, holdId, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_ALREADY_PAID"));
        getAs(fan, "/api/v1/orders").andExpect(jsonPath("$.content[0].id").value(orderId));

        // Purchased tickets count toward the per-fan limit of 4
        postAs(fan, "/api/v1/events/{id}/holds", seatsJson(seat(2), seat(3), seat(4)), event.eventId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_LIMIT_EXCEEDED"));
        hold(fan, seat(2), seat(3));
    }

    @Test
    void webhookSignatureIsVerified() throws Exception {
        String payload = stripeEvent("checkout.session.completed", 1, "paid", "pi_x");

        mockMvc.perform(post("/api/v1/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));

        mockMvc.perform(post("/api/v1/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload)
                        .header("Stripe-Signature", sign(payload, "whsec_wrong", Instant.now())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));

        // Valid signature but too old: replay protection
        mockMvc.perform(post("/api/v1/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload)
                        .header("Stripe-Signature", sign(payload, WEBHOOK_SECRET, Instant.now().minusSeconds(3600))))
                .andExpect(status().isBadRequest());

        // Tampered body
        String signed = sign(payload, WEBHOOK_SECRET, Instant.now());
        mockMvc.perform(post("/api/v1/webhooks/stripe").contentType(MediaType.APPLICATION_JSON)
                        .content(payload.replace("\"paid\"", "\"PAID\"")).header("Stripe-Signature", signed))
                .andExpect(status().isBadRequest());
    }

    @Test
    void expiredCheckoutReleasesSeats() throws Exception {
        long holdId = hold(fan, seat(0));
        long orderId = checkoutOrderId(fan, holdId);

        webhook(stripeEvent("checkout.session.expired", orderId, "unpaid", null))
                .andExpect(jsonPath("$.status").value("PROCESSED"));

        getAs(fan, "/api/v1/orders/{id}", orderId).andExpect(jsonPath("$.status").value("CANCELLED"));
        getAs(fan, "/api/v1/holds/{id}", holdId).andExpect(jsonPath("$.status").value("EXPIRED"));
        hold(tokenFor("FAN"), seat(0)); // back on sale
    }

    @Test
    void paymentAfterHoldWasLostIsRefundedAutomatically() throws Exception {
        long holdId = hold(fan, seat(0));
        long orderId = checkoutOrderId(fan, holdId);
        deleteAs(fan, "/api/v1/holds/{id}", holdId).andExpect(status().isNoContent());
        hold(tokenFor("FAN"), seat(0)); // someone else takes the seat

        // ... and then the first fan's payment completes anyway
        webhook(stripeEvent("checkout.session.completed", orderId, "paid", "pi_late_" + orderId))
                .andExpect(status().isOk());
        getAs(fan, "/api/v1/orders/{id}", orderId)
                .andExpect(jsonPath("$.status").value("REFUND_PENDING"))
                .andExpect(jsonPath("$.tickets", hasSize(0)));

        outboxPublisher.processBatch();
        assertThat(stripe.refundedPaymentIntents).contains("pi_late_" + orderId);
        assertThat(stripe.refundKeys).contains("refund-order-" + orderId);
        getAs(fan, "/api/v1/orders/{id}", orderId).andExpect(jsonPath("$.status").value("REFUNDED"));
    }

    @Test
    void stripeOutageDuringCheckoutCanBeRetriedWithTheSameKey() throws Exception {
        long holdId = hold(fan, seat(0));
        long nextOrderId = jdbc.queryForObject("select coalesce(max(id), 0) + 1 from orders", Long.class);
        stripe.failNextCheckoutFor(nextOrderId);

        String key = UUID.randomUUID().toString();
        checkout(fan, holdId, key)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PAYMENT_PROVIDER_ERROR"));

        // The failed attempt freed the key; the retry reuses the order and Stripe idempotency key
        checkout(fan, holdId, key)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(nextOrderId))
                .andExpect(jsonPath("$.checkoutUrl").isNotEmpty());
        assertThat(stripe.sessionRequests.stream().filter(r -> r.orderId() == nextOrderId)
                .map(r -> r.idempotencyKey()).distinct().toList())
                .containsExactly("checkout-order-" + nextOrderId);
    }

    @Test
    void failedOutboxDeliveryIsRetriedWithBackoff() throws Exception {
        long orderId = checkoutOrderId(fan, hold(fan, seat(0)));
        mailer.failNextSendTo(fanEmail);
        webhook(stripeEvent("checkout.session.completed", orderId, "paid", "pi_" + orderId));

        outboxPublisher.processBatch();
        assertThat(mailer.sentTo(fanEmail)).isEmpty();
        var row = jdbc.queryForMap("select status, attempts, last_error, next_attempt_at "
                + "from outbox_events where aggregate_id = ? and event_type = 'ORDER_PAID'", String.valueOf(orderId));
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat((String) row.get("last_error")).contains("Simulated SMTP failure");
        // next_attempt_at is written from the app clock, so compare against the app clock (not the DB clock)
        assertThat(((java.sql.Timestamp) row.get("next_attempt_at")).toInstant()).isAfter(Instant.now());

        jdbc.update("update outbox_events set next_attempt_at = now() - interval '1 hour' where aggregate_id = ? and event_type = 'ORDER_PAID'",
                String.valueOf(orderId));
        outboxPublisher.processBatch();
        assertThat(mailer.sentTo(fanEmail)).hasSize(1);
        assertThat(jdbc.queryForObject("select status from outbox_events where aggregate_id = ? and event_type = 'ORDER_PAID'",
                String.class, String.valueOf(orderId))).isEqualTo("DONE");
    }

    @Test
    void cannotCheckOutSomeoneElsesHold() throws Exception {
        long holdId = hold(fan, seat(0));
        checkout(tokenFor("FAN"), holdId, UUID.randomUUID().toString()).andExpect(status().isForbidden());
    }

    // ---------- helpers ----------

    private Long seat(int index) {
        return event.seatIds().get(index);
    }

    private long hold(String token, Long... seatIds) throws Exception {
        return ((Number) read(postAs(token, "/api/v1/events/{id}/holds", seatsJson(seatIds), event.eventId())
                .andExpect(status().isCreated()), "$.id")).longValue();
    }

    private static String seatsJson(Long... seatIds) {
        return "{\"seatIds\":[" + Arrays.stream(seatIds).map(String::valueOf).collect(Collectors.joining(",")) + "]}";
    }

    private ResultActions checkout(String token, long holdId, String idempotencyKey) throws Exception {
        var request = post("/api/v1/holds/{id}/checkout", holdId).header("Authorization", "Bearer " + token);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(request);
    }

    private long checkoutOrderId(String token, long holdId) throws Exception {
        return ((Number) read(checkout(token, holdId, UUID.randomUUID().toString()).andExpect(status().isCreated()),
                "$.orderId")).longValue();
    }

    private long ticketCount(long orderId) {
        return jdbc.queryForObject("select count(*) from tickets where order_id = ?", Long.class, orderId);
    }

    private ResultActions webhook(String payload) throws Exception {
        return mockMvc.perform(post("/api/v1/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload)
                .header("Stripe-Signature", sign(payload, WEBHOOK_SECRET, Instant.now())));
    }

    /** A minimal Stripe event envelope around a Checkout Session object. */
    private static String stripeEvent(String type, long orderId, String paymentStatus, String paymentIntent) {
        return """
                {"id":"evt_%s","object":"event","type":"%s","data":{"object":{
                  "id":"cs_test_order_%d","object":"checkout.session","payment_status":"%s",
                  "payment_intent":%s,"client_reference_id":"%d","metadata":{"order_id":"%d"}}}}"""
                .formatted(UUID.randomUUID(), type, orderId, paymentStatus,
                        paymentIntent == null ? "null" : "\"" + paymentIntent + "\"", orderId, orderId);
    }

    /** Stripe's scheme: header "t=<unix>,v1=<hex HMAC-SHA256(secret, t + "." + payload)>". */
    private static String sign(String payload, String secret, Instant at) throws Exception {
        long timestamp = at.getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(
                mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));
        return "t=" + timestamp + ",v1=" + signature;
    }
}
