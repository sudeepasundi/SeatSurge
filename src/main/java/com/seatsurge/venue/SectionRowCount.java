package com.seatsurge.venue;

/** Projection: number of seats in one row of a section. */
public record SectionRowCount(Long sectionId, String rowLabel, Long seatCount) {
}
