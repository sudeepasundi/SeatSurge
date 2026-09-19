package com.seatsurge.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.seatsurge.auth.dto.AuthDtos.AuthResponse;
import com.seatsurge.auth.dto.AuthDtos.LoginRequest;
import com.seatsurge.auth.dto.AuthDtos.RegisterRequest;
import com.seatsurge.common.config.SeatSurgeProperties;
import com.seatsurge.common.exception.ApiException;
import com.seatsurge.common.exception.BadRequestException;
import com.seatsurge.common.exception.ConflictException;
import com.seatsurge.common.ratelimit.RateLimiter;
import com.seatsurge.user.Role;
import com.seatsurge.user.User;
import com.seatsurge.user.UserRepository;
import com.seatsurge.user.UserResponse;

@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final Duration refreshTokenTtl;
    private final Clock clock;
    private final RateLimiter rateLimiter;
    private final int loginsPerMinute;

    public AuthService(UserRepository userRepository, RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder, JwtService jwtService, SeatSurgeProperties properties, Clock clock,
            RateLimiter rateLimiter) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenTtl = properties.jwt().refreshTokenTtl();
        this.clock = clock;
        this.rateLimiter = rateLimiter;
        this.loginsPerMinute = properties.rateLimit().loginsPerMinute();
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        Role role = request.role() == null ? Role.FAN : request.role();
        if (!role.isSelfAssignable()) {
            throw new BadRequestException("ROLE_NOT_ALLOWED", "Only FAN or ORGANIZER can be chosen at sign-up");
        }
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("EMAIL_TAKEN", "An account with this email already exists");
        }
        User user = userRepository.save(new User(email, passwordEncoder.encode(request.password()),
                request.fullName().trim(), role));
        return issueTokens(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        // Per-account throttle against password guessing (counts every attempt, successful or not).
        rateLimiter.check("login:" + email, loginsPerMinute, Duration.ofMinutes(1));
        User user = userRepository.findByEmail(email)
                .filter(u -> passwordEncoder.matches(request.password(), u.getPasswordHash()))
                .filter(User::isEnabled)
                .orElseThrow(() -> unauthorized("INVALID_CREDENTIALS", "Invalid email or password"));
        return issueTokens(user);
    }

    /**
     * Refresh-token rotation: every refresh token is single-use. Presenting an already-used token means it
     * was probably stolen, so the whole token family of that user is revoked (reuse detection).
     * noRollbackFor keeps that revocation committed even though we answer with an error.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public AuthResponse refresh(String rawRefreshToken) {
        RefreshToken token = refreshTokenRepository.findByTokenHash(hash(rawRefreshToken))
                .orElseThrow(() -> unauthorized("INVALID_REFRESH_TOKEN", "Refresh token is invalid"));
        User user = token.getUser();

        if (token.isRevoked() || refreshTokenRepository.revokeIfActive(token.getId()) == 0) {
            refreshTokenRepository.revokeAllForUser(user.getId());
            throw unauthorized("REFRESH_TOKEN_REUSED", "Refresh token was already used; please log in again");
        }
        if (token.isExpired(clock.instant()) || !user.isEnabled()) {
            throw unauthorized("INVALID_REFRESH_TOKEN", "Refresh token is invalid");
        }
        return issueTokens(user);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenRepository.findByTokenHash(hash(rawRefreshToken))
                .ifPresent(token -> refreshTokenRepository.revokeIfActive(token.getId()));
    }

    private AuthResponse issueTokens(User user) {
        String rawRefreshToken = newOpaqueToken();
        refreshTokenRepository.save(new RefreshToken(user, hash(rawRefreshToken),
                clock.instant().plus(refreshTokenTtl)));
        return new AuthResponse(jwtService.issueAccessToken(user), "Bearer",
                jwtService.accessTokenTtl().toSeconds(), rawRefreshToken, UserResponse.from(user));
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String newOpaqueToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static ApiException unauthorized(String code, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, message);
    }
}
