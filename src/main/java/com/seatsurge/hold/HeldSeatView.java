package com.seatsurge.hold;

/** Projection: a seat inside a hold, with its location and price. */
public record HeldSeatView(Long eventSeatId, String section, String row, Integer number, Long priceCents,
        String currency) {
}
