package com.seatsurge.event;

/** Organizer dashboard numbers for one event. */
public record EventStats(Long eventId, EventStatus status, long totalSeats, long availableSeats, long heldSeats,
        long soldSeats, long paidOrders, long revenueCents, String currency, long checkedIn) {
}
