package com.seatsurge.admin;

import java.util.Locale;

import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.seatsurge.admin.AdminDtos.CreateUserRequest;
import com.seatsurge.auth.AuthUser;
import com.seatsurge.auth.RefreshTokenRepository;
import com.seatsurge.common.exception.BadRequestException;
import com.seatsurge.common.exception.ConflictException;
import com.seatsurge.common.exception.NotFoundException;
import com.seatsurge.common.web.PageResponse;
import com.seatsurge.user.Role;
import com.seatsurge.user.User;
import com.seatsurge.user.UserRepository;
import com.seatsurge.user.UserResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;

    /** Creates accounts with any role, e.g. GATE_STAFF for venue entrances. */
    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("EMAIL_TAKEN", "An account with this email already exists");
        }
        return UserResponse.from(userRepository.save(new User(email, passwordEncoder.encode(request.password()),
                request.fullName().trim(), request.role())));
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminDtos.AdminUserView> list(Role role, Pageable pageable) {
        var page = role == null ? userRepository.findAll(pageable) : userRepository.findByRole(role, pageable);
        return PageResponse.of(page, AdminDtos.AdminUserView::from);
    }

    /** Disabling blocks login and refresh immediately; issued access tokens simply expire (15 min). */
    @Transactional
    public AdminDtos.AdminUserView setEnabled(Long userId, boolean enabled, AuthUser admin) {
        if (userId.equals(admin.id()) && !enabled) {
            throw new BadRequestException("CANNOT_DISABLE_SELF", "Admins cannot disable their own account");
        }
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));
        user.setEnabled(enabled);
        if (!enabled) {
            refreshTokenRepository.revokeAllForUser(userId);
        }
        return AdminDtos.AdminUserView.from(userRepository.findById(userId).orElseThrow());
    }
}
