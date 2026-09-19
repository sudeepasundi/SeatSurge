package com.seatsurge.seat;

/** Flat projection of one seat for the seat map; grouped into sections/rows in the service. */
public record SeatMapRow(Long eventSeatId, Long sectionId, String sectionName, String rowLabel, Integer seatNumber,
        SeatStatus status, Long tierId, String tierName, Long priceCents) {
}
