package com.seatsurge.event;

/** Projection: cheapest ticket price of an event (for search listings). */
public record EventPrice(Long eventId, Long minPriceCents, String currency) {
}
