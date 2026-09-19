package com.seatsurge.auth;

import com.seatsurge.user.Role;

/** Principal stored in the SecurityContext, built from verified JWT claims (no DB hit per request). */
public record AuthUser(Long id, String email, Role role) {
}
