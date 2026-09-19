package com.seatsurge.event;

/** Projection: seat totals for one price tier of an event. */
public record TierAvailability(Long tierId, Long totalSeats, Long availableSeats) {
}
