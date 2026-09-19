package com.seatsurge.ticket;

public enum TicketStatus {
    VALID,
    /** Voided, e.g. because the order was refunded or the event cancelled. */
    CANCELLED
}
