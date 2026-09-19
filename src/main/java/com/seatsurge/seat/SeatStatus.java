package com.seatsurge.seat;

public enum SeatStatus {
    AVAILABLE,
    /** Temporarily reserved by a checkout in progress; returns to AVAILABLE if the hold expires. */
    HELD,
    SOLD
}
