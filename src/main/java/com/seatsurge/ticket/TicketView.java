package com.seatsurge.ticket;

import java.time.Instant;
import java.util.UUID;

/** Projection: a ticket with its seat location. */
public record TicketView(Long id, UUID code, TicketStatus status, Long eventId, String section, String row,
        Integer number, Instant checkedInAt) {
}
