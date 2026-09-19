package com.seatsurge.event;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.seatsurge.support.IntegrationTest;

class CatalogIntegrationTest extends IntegrationTest {

    private String organizer;
    private String city;
    private long venueId;
    private long floorId;
    private long balconyId;

    /** Venue "Floor" (rows A,B x 10 seats) + "Balcony" (row A x 5 seats) = 25 seats. */
    @BeforeEach
    void setUpVenue() throws Exception {
        organizer = tokenFor("ORGANIZER");
        city = "City-" + UUID.randomUUID();
        venueId = ((Number) read(postAs(organizer, "/api/v1/venues", """
                {"name":"Test Arena","address":"1 Main St","city":"%s"}""".formatted(city))
                .andExpect(status().isCreated()), "$.id")).longValue();

        floorId = sectionId(postAs(organizer, "/api/v1/venues/{id}/sections", """
                {"name":"Floor","rows":[{"label":"a","seatCount":10},{"label":"B","seatCount":10}]}""", venueId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.capacity").value(20)), "Floor");

        balconyId = sectionId(postAs(organizer, "/api/v1/venues/{id}/sections", """
                {"name":"Balcony","rows":[{"label":"A","seatCount":5}]}""", venueId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.capacity").value(25))
                .andExpect(jsonPath("$.sections[?(@.name=='Floor')].rows[*].label", contains("A", "B"))),
                "Balcony");
    }

    @Test
    void organizerCreatesPublishesAndFansDiscoverTheEvent() throws Exception {
        long eventId = createEvent(twoTierEvent());

        // Drafts are private
        getAs(null, "/api/v1/events/{id}", eventId).andExpect(status().isNotFound());
        getAs(organizer, "/api/v1/events/{id}", eventId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.salePhase").value("UPCOMING"));

        postAs(organizer, "/api/v1/events/{id}/publish", null, eventId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.salePhase").value("ON_SALE"))
                .andExpect(jsonPath("$.totalSeats").value(25))
                .andExpect(jsonPath("$.availableSeats").value(25))
                .andExpect(jsonPath("$.priceTiers[*].name", contains("VIP", "Standard")))
                .andExpect(jsonPath("$.priceTiers[0].totalSeats").value(20))
                .andExpect(jsonPath("$.priceTiers[1].totalSeats").value(5));

        // Public search by city and by artist text
        getAs(null, "/api/v1/events?city={city}", city.toUpperCase())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(eventId))
                .andExpect(jsonPath("$.content[0].minPriceCents").value(5000))
                .andExpect(jsonPath("$.content[0].currency").value("usd"));
        getAs(null, "/api/v1/events?city={city}&q=lumin", city)
                .andExpect(jsonPath("$.totalElements").value(1));
        getAs(null, "/api/v1/events?city={city}&q=nobody", city)
                .andExpect(jsonPath("$.totalElements").value(0));

        // Seat map: sections sorted by name, rows and seats in order
        getAs(null, "/api/v1/events/{id}/seats", eventId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableSeats").value(25))
                .andExpect(jsonPath("$.sections[*].name", contains("Balcony", "Floor")))
                .andExpect(jsonPath("$.sections[1].tierName").value("VIP"))
                .andExpect(jsonPath("$.sections[1].priceCents").value(15000))
                .andExpect(jsonPath("$.sections[1].rows[*].label", contains("A", "B")))
                .andExpect(jsonPath("$.sections[1].rows[0].seats", hasSize(10)))
                .andExpect(jsonPath("$.sections[1].rows[0].seats[0].number").value(1))
                .andExpect(jsonPath("$.sections[1].rows[0].seats[0].status").value("AVAILABLE"));

        // Layout and pricing are frozen once published
        putAs(organizer, "/api/v1/events/{id}", twoTierEvent(), eventId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_EVENT_STATUS"));
        postAs(organizer, "/api/v1/venues/{id}/sections", """
                {"name":"Box","rows":[{"label":"A","seatCount":2}]}""", venueId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VENUE_IN_USE"));

        // Cancelled events drop out of search
        postAs(organizer, "/api/v1/events/{id}/cancel", null, eventId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.salePhase").value("CLOSED"));
        getAs(null, "/api/v1/events?city={city}", city).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void draftCanBeRepricedAndDeleted() throws Exception {
        long eventId = createEvent(twoTierEvent());

        putAs(organizer, "/api/v1/events/{id}", eventJson("""
                [{"name":"General","priceCents":7500,"sectionIds":[%d,%d]}]""".formatted(floorId, balconyId),
                "2030-01-01T18:00:00Z", "2029-12-01T10:00:00Z"), eventId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceTiers", hasSize(1)))
                .andExpect(jsonPath("$.priceTiers[0].totalSeats").value(25));

        getAs(organizer, "/api/v1/events/mine").andExpect(status().isOk());

        deleteAs(organizer, "/api/v1/events/{id}", eventId).andExpect(status().isNoContent());
        getAs(organizer, "/api/v1/events/{id}", eventId).andExpect(status().isNotFound());
    }

    @Test
    void eventValidationRules() throws Exception {
        postAs(organizer, "/api/v1/events", eventJson(tiers(), "2030-01-01T18:00:00Z", "2030-02-01T10:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SCHEDULE"));

        postAs(organizer, "/api/v1/events", eventJson("""
                [{"name":"A","priceCents":100,"sectionIds":[%d]},{"name":"B","priceCents":200,"sectionIds":[%d]}]"""
                .formatted(floorId, floorId), "2030-01-01T18:00:00Z", "2029-12-01T10:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SECTION_PRICED_TWICE"));

        postAs(organizer, "/api/v1/events", eventJson("""
                [{"name":"A","priceCents":100,"sectionIds":[999999]}]""",
                "2030-01-01T18:00:00Z", "2029-12-01T10:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_SECTION"));

        postAs(organizer, "/api/v1/venues/{id}/sections", """
                {"name":"floor","rows":[{"label":"Z","seatCount":1}]}""", venueId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SECTION_EXISTS"));
    }

    @Test
    void rolesAndOwnershipAreEnforced() throws Exception {
        String fan = tokenFor("FAN");
        postAs(fan, "/api/v1/venues", """
                {"name":"X","address":"Y","city":"Z"}""").andExpect(status().isForbidden());
        postAs(fan, "/api/v1/events", twoTierEvent()).andExpect(status().isForbidden());

        String otherOrganizer = tokenFor("ORGANIZER");
        getAs(otherOrganizer, "/api/v1/venues/{id}", venueId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_OWNER"));
        postAs(otherOrganizer, "/api/v1/events", twoTierEvent())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_OWNER"));

        long eventId = createEvent(twoTierEvent());
        postAs(otherOrganizer, "/api/v1/events/{id}/publish", null, eventId).andExpect(status().isForbidden());
        getAs(otherOrganizer, "/api/v1/events/{id}", eventId).andExpect(status().isNotFound());

        getAs(null, "/api/v1/events/mine").andExpect(status().isUnauthorized());
        getAs(null, "/api/v1/venues").andExpect(status().isUnauthorized());
    }

    // ---------- helpers ----------

    private long createEvent(String json) throws Exception {
        return ((Number) read(postAs(organizer, "/api/v1/events", json).andExpect(status().isCreated()), "$.id"))
                .longValue();
    }

    private String twoTierEvent() {
        Instant startsAt = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Instant saleStartsAt = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        return eventJson(tiers(), startsAt.toString(), saleStartsAt.toString());
    }

    private String tiers() {
        return """
                [{"name":"VIP","priceCents":15000,"sectionIds":[%d]},
                 {"name":"Standard","priceCents":5000,"sectionIds":[%d]}]""".formatted(floorId, balconyId);
    }

    private String eventJson(String tiers, String startsAt, String saleStartsAt) {
        return """
                {"venueId":%d,"title":"Luminous World Tour","artist":"The Luminators","category":"concert",
                 "startsAt":"%s","saleStartsAt":"%s","maxTicketsPerUser":4,"priceTiers":%s}"""
                .formatted(venueId, startsAt, saleStartsAt, tiers);
    }

    private static long sectionId(org.springframework.test.web.servlet.ResultActions result, String name)
            throws Exception {
        net.minidev.json.JSONArray ids = read(result, "$.sections[?(@.name=='" + name + "')].id");
        return ((Number) ids.getFirst()).longValue();
    }
}
