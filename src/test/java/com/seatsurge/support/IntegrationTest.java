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
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

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
        long venueId = ((Number) read(postAs(organizerToken, "/api/v1/venues", """
                {"name":"Arena","address":"1 Main St","city":"City-%s"}""".formatted(UUID.randomUUID()))
                .andExpect(status().isCreated()), "$.id")).longValue();
        List<Number> sectionIds = read(postAs(organizerToken, "/api/v1/venues/{id}/sections", """
                {"name":"Main","rows":[{"label":"A","seatCount":%d}]}""".formatted(seats), venueId)
                .andExpect(status().isCreated()), "$.sections[*].id");

        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant saleStartsAt = onSaleNow ? now.minus(1, ChronoUnit.HOURS) : now.plus(1, ChronoUnit.DAYS);
        long eventId = ((Number) read(postAs(organizerToken, "/api/v1/events", """
                {"venueId":%d,"title":"Flash Sale Night","artist":"Rush","startsAt":"%s","saleStartsAt":"%s",
                 "maxTicketsPerUser":%d,"priceTiers":[{"name":"GA","priceCents":5000,"sectionIds":[%d]}]}"""
                .formatted(venueId, now.plus(30, ChronoUnit.DAYS), saleStartsAt, maxTicketsPerUser,
                        sectionIds.getFirst().longValue()))
                .andExpect(status().isCreated()), "$.id")).longValue();
        postAs(organizerToken, "/api/v1/events/{id}/publish", null, eventId).andExpect(status().isOk());

        List<Number> seatIds = read(getAs(organizerToken, "/api/v1/events/{id}/seats", eventId),
                "$.sections[0].rows[0].seats[*].id");
        return new TestEvent(eventId, seatIds.stream().map(Number::longValue).toList());
    }

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
