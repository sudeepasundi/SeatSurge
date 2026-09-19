package com.seatsurge.user;

public enum Role {
    /** Buys tickets. */
    FAN,
    /** Creates venues and events, sees sales. */
    ORGANIZER,
    /** Scans tickets at the venue gate. */
    GATE_STAFF,
    /** Platform administrator. */
    ADMIN;

    /** Roles a user may pick for themselves at sign-up; the rest are granted by an admin. */
    public boolean isSelfAssignable() {
        return this == FAN || this == ORGANIZER;
    }
}
