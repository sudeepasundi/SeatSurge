package com.seatsurge.admin;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.seatsurge.admin.AdminDtos.AdminUserView;
import com.seatsurge.admin.AdminDtos.CreateUserRequest;
import com.seatsurge.admin.AdminDtos.UserStatusRequest;
import com.seatsurge.auth.AuthUser;
import com.seatsurge.common.web.PageResponse;
import com.seatsurge.user.Role;
import com.seatsurge.user.UserResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin")
public class AdminController {

    private final AdminService adminService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a user with any role (e.g. GATE_STAFF)")
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        return adminService.createUser(request);
    }

    @GetMapping
    @Operation(summary = "List users, optionally filtered by role")
    public PageResponse<AdminUserView> list(@RequestParam(required = false) Role role,
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return adminService.list(role, pageable);
    }

    @PutMapping("/{userId}/status")
    @Operation(summary = "Enable or disable an account (disabling revokes its refresh tokens)")
    public AdminUserView setStatus(@AuthenticationPrincipal AuthUser admin, @PathVariable Long userId,
            @Valid @RequestBody UserStatusRequest request) {
        return adminService.setEnabled(userId, request.enabled(), admin);
    }
}
