package com.seatsurge.ticket;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** Projection: a ticket as its owner sees it, with event and seat details. */
public record MyTicket(Long id, UUID code, TicketStatus status, Long eventId, String eventTitle,
        Instant eventStartsAt, String venue, String city, String section, String row, Integer seat,
        Instant checkedInAt, @JsonIgnore Long ownerId) {
}
