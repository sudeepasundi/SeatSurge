package com.seatsurge.event;

public enum EventStatus {
    /** Being set up by the organizer; invisible to the public, fully editable. */
    DRAFT,
    /** Visible and (once saleStartsAt passes) purchasable. Layout and pricing are frozen. */
    PUBLISHED,
    /** Called off by the organizer. */
    CANCELLED
}
