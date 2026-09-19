package com.seatsurge.auth;

import com.seatsurge.common.exception.ForbiddenException;
import com.seatsurge.user.Role;

/** Principal stored in the SecurityContext, built from verified JWT claims (no DB hit per request). */
public record AuthUser(Long id, String email, Role role) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    /** Admins manage everything; everyone else only what they own. */
    public boolean canManage(Long ownerId) {
        return isAdmin() || id.equals(ownerId);
    }

    public void requireCanManage(Long ownerId, String resource) {
        if (!canManage(ownerId)) {
            throw new ForbiddenException("NOT_OWNER", "You can only manage your own " + resource);
        }
    }
}
