package com.seatsurge.waitingroom;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.seatsurge.event.Event;

/** Plain unit test of the admission formula: no Spring, no Redis. */
class AdmissionAllowanceTest {

    private static final Instant SALE_START = Instant.parse("2030-06-01T10:00:00Z");

    @Test
    void nobodyIsAdmittedBeforeTheSaleOpens() {
        assertThat(WaitingRoomService.allowance(event(600), SALE_START.minusSeconds(1))).isZero();
    }

    @Test
    void oneMinuteWorthIsAdmittedAtOpeningThenRateGrowsLinearly() {
        Event event = event(600);
        assertThat(WaitingRoomService.allowance(event, SALE_START)).isEqualTo(600);
        assertThat(WaitingRoomService.allowance(event, SALE_START.plusSeconds(30))).isEqualTo(900);
        assertThat(WaitingRoomService.allowance(event, SALE_START.plusSeconds(60))).isEqualTo(1200);
        assertThat(WaitingRoomService.allowance(event, SALE_START.plusSeconds(600))).isEqualTo(6600);
    }

    @Test
    void slowRatesAdmitInWholeSteps() {
        Event event = event(2); // one fan every 30 seconds
        assertThat(WaitingRoomService.allowance(event, SALE_START.plusSeconds(29))).isEqualTo(2);
        assertThat(WaitingRoomService.allowance(event, SALE_START.plusSeconds(30))).isEqualTo(3);
    }

    private static Event event(int ratePerMinute) {
        Event event = new Event(null);
        event.setSaleStartsAt(SALE_START);
        event.setAdmissionRatePerMinute(ratePerMinute);
        return event;
    }
}
