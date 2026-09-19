package com.seatsurge.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.seatsurge.TestcontainersConfiguration;

/** Base for full-stack tests: real Postgres + Redis via Testcontainers, shared Spring context. */
@SpringBootTest(properties = {
        "seatsurge.stripe.webhook-secret=" + IntegrationTest.WEBHOOK_SECRET,
        "seatsurge.admin.email=" + IntegrationTest.ADMIN_EMAIL,
        "seatsurge.admin.password=" + IntegrationTest.ADMIN_PASSWORD,
        // Background jobs are driven explicitly by tests for deterministic assertions
        "seatsurge.outbox.poll-interval=1h",
        "seatsurge.hold.sweep-interval=1h"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestStubsConfiguration.class})
public abstract class IntegrationTest {

    public static final String WEBHOOK_SECRET = "whsec_test_seatsurge";
    public static final String ADMIN_EMAIL = "admin@seatsurge.test";
    public static final String ADMIN_PASSWORD = "admin-pass-123";

    @Autowired
    protected MockMvc mockMvc;

    /** Registers a fresh user with the given self-assignable role and returns its access token. */
    protected String tokenFor(String role) throws Exception {
        String email = role.toLowerCase() + "-" + UUID.randomUUID() + "@example.com";
        String body = mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"s3cure-pass","fullName":"Test %s","role":"%s"}
                                """.formatted(email, role, role)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    public record TestEvent(long eventId, List<Long> seatIds) {
    }

    /**
     * Creates a venue with one section (row A, {@code seats} seats, $50 each) and a published event.
     * The sale is open now if {@code onSaleNow}, otherwise it opens tomorrow.
     */
    protected TestEvent createPublishedEvent(String organizerToken, int seats, int maxTicketsPerUser,
            boolean onSaleNow) throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        return createPublishedEvent(organizerToken, seats, maxTicketsPerUser,
                onSaleNow ? now.minus(1, ChronoUnit.HOURS) : now.plus(1, ChronoUnit.DAYS), "");
    }

    /** Full control over the sale start, plus extra JSON fields for the event (e.g. waiting-room settings). */
    protected TestEvent createPublishedEvent(String organizerToken, int seats, int maxTicketsPerUser,
            Instant saleStartsAt, String extraJsonFields) throws Exception {
        long venueId = ((Number) read(postAs(organizerToken, "/api/v1/venues", """
                {"name":"Arena","address":"1 Main St","city":"City-%s"}""".formatted(UUID.randomUUID()))
                .andExpect(status().isCreated()), "$.id")).longValue();
        List<Number> sectionIds = read(postAs(organizerToken, "/api/v1/venues/{id}/sections", """
                {"name":"Main","rows":[{"label":"A","seatCount":%d}]}""".formatted(seats), venueId)
                .andExpect(status().isCreated()), "$.sections[*].id");

        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String extra = extraJsonFields == null || extraJsonFields.isBlank() ? "" : "," + extraJsonFields;
        long eventId = ((Number) read(postAs(organizerToken, "/api/v1/events", """
                {"venueId":%d,"title":"Flash Sale Night","artist":"Rush","startsAt":"%s","saleStartsAt":"%s",
                 "maxTicketsPerUser":%d,"priceTiers":[{"name":"GA","priceCents":5000,"sectionIds":[%d]}]%s}"""
                .formatted(venueId, now.plus(30, ChronoUnit.DAYS), saleStartsAt, maxTicketsPerUser,
                        sectionIds.getFirst().longValue(), extra))
                .andExpect(status().isCreated()), "$.id")).longValue();
        postAs(organizerToken, "/api/v1/events/{id}/publish", null, eventId).andExpect(status().isOk());

        List<Number> seatIds = read(getAs(organizerToken, "/api/v1/events/{id}/seats", eventId),
                "$.sections[0].rows[0].seats[*].id");
        return new TestEvent(eventId, seatIds.stream().map(Number::longValue).toList());
    }

    // ---------- users ----------

    protected String login(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private static String cachedAdminToken;

    /** Cached: logins are rate limited per account, and the token outlives the whole test run. */
    protected String adminToken() throws Exception {
        if (cachedAdminToken == null) {
            cachedAdminToken = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        }
        return cachedAdminToken;
    }

    /** Roles that cannot self-register (GATE_STAFF, ADMIN) are created through the admin API. */
    protected String tokenForStaff(String role) throws Exception {
        String email = role.toLowerCase() + "-" + UUID.randomUUID() + "@example.com";
        postAs(adminToken(), "/api/v1/admin/users", """
                {"email":"%s","password":"staff-pass-123","fullName":"Staff","role":"%s"}"""
                .formatted(email, role)).andExpect(status().isCreated());
        return login(email, "staff-pass-123");
    }

    // ---------- purchasing ----------

    /** Full purchase: hold -> checkout -> signed "paid" webhook. Returns the order id. */
    protected long purchase(String fanToken, long eventId, Long... seatIds) throws Exception {
        String ids = java.util.Arrays.stream(seatIds).map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        long holdId = ((Number) read(postAs(fanToken, "/api/v1/events/{id}/holds", "{\"seatIds\":[" + ids + "]}", eventId)
                .andExpect(status().isCreated()), "$.id")).longValue();
        long orderId = ((Number) read(mockMvc.perform(post("/api/v1/holds/{id}/checkout", holdId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fanToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()), "$.orderId")).longValue();
        webhook(stripeEvent("checkout.session.completed", orderId, "paid", "pi_" + orderId))
                .andExpect(status().isOk());
        return orderId;
    }

    protected ResultActions webhook(String payload) throws Exception {
        return mockMvc.perform(post("/api/v1/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload)
                .header("Stripe-Signature", sign(payload, WEBHOOK_SECRET, Instant.now())));
    }

    /** A minimal Stripe event envelope around a Checkout Session object. */
    protected static String stripeEvent(String type, long orderId, String paymentStatus, String paymentIntent) {
        return """
                {"id":"evt_%s","object":"event","type":"%s","data":{"object":{
                  "id":"cs_test_order_%d","object":"checkout.session","payment_status":"%s",
                  "payment_intent":%s,"client_reference_id":"%d","metadata":{"order_id":"%d"}}}}"""
                .formatted(UUID.randomUUID(), type, orderId, paymentStatus,
                        paymentIntent == null ? "null" : "\"" + paymentIntent + "\"", orderId, orderId);
    }

    /** Stripe's scheme: header "t=<unix>,v1=<hex HMAC-SHA256(secret, t + "." + payload)>". */
    protected static String sign(String payload, String secret, Instant at) throws Exception {
        long timestamp = at.getEpochSecond();
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "HmacSHA256"));
        String signature = java.util.HexFormat.of().formatHex(
                mac.doFinal((timestamp + "." + payload).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return "t=" + timestamp + ",v1=" + signature;
    }

    // ---------- HTTP helpers ----------

    protected ResultActions getAs(String token, String url, Object... vars) throws Exception {
        return mockMvc.perform(withAuth(get(url, vars), token));
    }

    protected ResultActions postAs(String token, String url, String json, Object... vars) throws Exception {
        MockHttpServletRequestBuilder req = post(url, vars);
        if (json != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(withAuth(req, token));
    }

    protected ResultActions putAs(String token, String url, String json, Object... vars) throws Exception {
        return mockMvc.perform(withAuth(put(url, vars).contentType(MediaType.APPLICATION_JSON).content(json), token));
    }

    protected ResultActions deleteAs(String token, String url, Object... vars) throws Exception {
        return mockMvc.perform(withAuth(delete(url, vars), token));
    }

    protected static <T> T read(ResultActions result, String jsonPath) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), jsonPath);
    }

    private static MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder req, String token) {
        return token == null ? req : req.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
