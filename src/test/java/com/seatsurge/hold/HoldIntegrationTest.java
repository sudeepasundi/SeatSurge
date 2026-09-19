package com.seatsurge.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.seatsurge.support.IntegrationTest;

class HoldIntegrationTest extends IntegrationTest {

    @Autowired
    private HoldService holdService;

    @Autowired
    private JdbcTemplate jdbc;

    private String organizer;
    private TestEvent event;

    @BeforeEach
    void setUp() throws Exception {
        organizer = tokenFor("ORGANIZER");
        event = createPublishedEvent(organizer, 10, 4, true);
    }

    @Test
    void fanHoldsSeatsAndOthersCannotTakeThem() throws Exception {
        String alice = tokenFor("FAN");
        String bob = tokenFor("FAN");
        long s1 = event.seatIds().get(0);
        long s2 = event.seatIds().get(1);

        long holdId = ((Number) read(hold(alice, s1, s2)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.seats", hasSize(2)))
                .andExpect(jsonPath("$.seats[0].row").value("A"))
                .andExpect(jsonPath("$.totalCents").value(10000))
                .andExpect(jsonPath("$.currency").value("usd"))
                .andExpect(jsonPath("$.secondsRemaining").value(org.hamcrest.Matchers.greaterThan(500))), "$.id"))
                .longValue();

        getAs(null, "/api/v1/events/{id}/seats", event.eventId())
                .andExpect(jsonPath("$.availableSeats").value(8))
                .andExpect(jsonPath("$.sections[0].rows[0].seats[0].status").value("HELD"));

        // Overlapping request is rejected as a whole (all-or-nothing)
        hold(bob, s2, event.seatIds().get(2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEATS_UNAVAILABLE"));
        getAs(null, "/api/v1/events/{id}/seats", event.eventId()).andExpect(jsonPath("$.availableSeats").value(8));

        // Holds are private
        getAs(bob, "/api/v1/holds/{id}", holdId).andExpect(status().isForbidden());
        deleteAs(bob, "/api/v1/holds/{id}", holdId).andExpect(status().isForbidden());
        getAs(alice, "/api/v1/holds").andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void releasedSeatsAreImmediatelyAvailableAgain() throws Exception {
        String alice = tokenFor("FAN");
        String bob = tokenFor("FAN");
        long seat = event.seatIds().get(0);

        long holdId = ((Number) read(hold(alice, seat).andExpect(status().isCreated()), "$.id")).longValue();
        deleteAs(alice, "/api/v1/holds/{id}", holdId).andExpect(status().isNoContent());
        deleteAs(alice, "/api/v1/holds/{id}", holdId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_NOT_ACTIVE"));
        getAs(alice, "/api/v1/holds/{id}", holdId).andExpect(jsonPath("$.status").value("RELEASED"));

        // Redis lock was released too, so Bob does not have to wait for its TTL
        hold(bob, seat).andExpect(status().isCreated());
    }

    @Test
    void expiredHoldsAreSweptBackOnSale() throws Exception {
        String alice = tokenFor("FAN");
        String bob = tokenFor("FAN");
        long seat = event.seatIds().get(0);

        long holdId = ((Number) read(hold(alice, seat).andExpect(status().isCreated()), "$.id")).longValue();
        jdbc.update("update holds set expires_at = now() - interval '1 hour' where id = ?", holdId);

        assertThat(holdService.expireDueHolds()).isGreaterThanOrEqualTo(1);
        getAs(alice, "/api/v1/holds/{id}", holdId).andExpect(jsonPath("$.status").value("EXPIRED"));
        hold(bob, seat).andExpect(status().isCreated());
    }

    @Test
    void perFanLimits() throws Exception {
        String alice = tokenFor("FAN");
        var seats = event.seatIds();

        // Event allows 4 tickets per fan
        hold(alice, seats.get(0), seats.get(1), seats.get(2), seats.get(3), seats.get(4))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_LIMIT_EXCEEDED"));

        hold(alice, seats.get(0)).andExpect(status().isCreated());
        hold(alice, seats.get(1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_HOLD_EXISTS"));

        hold(alice, seats.get(2), seats.get(2))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_SEATS"));
    }

    @Test
    void holdRules() throws Exception {
        String fan = tokenFor("FAN");

        TestEvent notYetOnSale = createPublishedEvent(organizer, 5, 4, false);
        holdOn(fan, notYetOnSale.eventId(), notYetOnSale.seatIds().get(0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_ON_SALE"));

        // A seat from a different event
        holdOn(fan, event.eventId(), notYetOnSale.seatIds().get(0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_SEAT"));

        // Only fans buy tickets
        hold(organizer, event.seatIds().get(0)).andExpect(status().isForbidden());
        hold(null, event.seatIds().get(0)).andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.ResultActions hold(String token, Long... seatIds) throws Exception {
        return holdOn(token, event.eventId(), seatIds);
    }

    private org.springframework.test.web.servlet.ResultActions holdOn(String token, long eventId, Long... seatIds)
            throws Exception {
        String ids = java.util.Arrays.stream(seatIds).map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        return postAs(token, "/api/v1/events/{id}/holds", "{\"seatIds\":[" + ids + "]}", eventId);
    }
}
