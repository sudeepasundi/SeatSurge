package com.seatsurge.event;

/** Derived from status + clock, so no scheduler is needed to "open" a sale at the right instant. */
public enum SalePhase {
    UPCOMING,
    ON_SALE,
    CLOSED
}
