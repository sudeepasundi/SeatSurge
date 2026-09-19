package com.seatsurge.admin;

import java.time.Instant;

import com.seatsurge.user.Role;
import com.seatsurge.user.User;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class AdminDtos {

    private AdminDtos() {
    }

    public record CreateUserRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(max = 120) String fullName,
            @NotNull Role role) {
    }

    public record UserStatusRequest(@NotNull Boolean enabled) {
    }

    public record AdminUserView(Long id, String email, String fullName, Role role, boolean enabled, Instant createdAt) {

        static AdminUserView from(User user) {
            return new AdminUserView(user.getId(), user.getEmail(), user.getFullName(), user.getRole(),
                    user.isEnabled(), user.getCreatedAt());
        }
    }
}
