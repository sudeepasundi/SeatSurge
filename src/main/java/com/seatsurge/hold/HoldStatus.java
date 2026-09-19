package com.seatsurge.hold;

public enum HoldStatus {
    /** Seats are reserved for this fan until expiresAt. */
    ACTIVE,
    /** Paid: the seats became tickets. */
    CONVERTED,
    /** Timed out; seats went back on sale. */
    EXPIRED,
    /** Given up by the fan; seats went back on sale. */
    RELEASED
}
