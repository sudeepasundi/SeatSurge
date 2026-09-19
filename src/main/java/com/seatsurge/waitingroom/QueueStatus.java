package com.seatsurge.waitingroom;

import java.time.Instant;

import com.seatsurge.event.SalePhase;

/**
 * A fan's place in the waiting room. Once {@code admitted}, {@code admissionToken} must be sent as the
 * X-Admission-Token header when holding seats; poll again for a fresh token after it expires.
 */
public record QueueStatus(Long eventId, SalePhase salePhase, long queueNumber, boolean admitted, long peopleAhead,
        long estimatedWaitSeconds, String admissionToken, Instant admissionExpiresAt) {
}
