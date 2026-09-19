package com.seatsurge.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import com.seatsurge.common.config.SeatSurgeProperties;
import com.seatsurge.user.Role;
import com.seatsurge.user.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

/**
 * Issues and verifies two kinds of JWT, told apart by the "typ" claim so one can never be used as the
 * other: access tokens (who you are) and waiting-room admission tokens (you may shop this event now).
 */
@Service
public class JwtService {

    private static final String ISSUER = "seatsurge";
    private static final String CLAIM_TYPE = "typ";
    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_EVENT = "evt";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_ADMISSION = "admission";

    private final SecretKey key;
    private final Duration accessTokenTtl;
    private final Clock clock;

    public JwtService(SeatSurgeProperties properties, Clock clock) {
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(properties.jwt().secret()));
        this.accessTokenTtl = properties.jwt().accessTokenTtl();
        this.clock = clock;
    }

    public String issueAccessToken(User user) {
        Instant now = clock.instant();
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(user.getId().toString())
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .claim(CLAIM_EMAIL, user.getEmail())
                .claim(CLAIM_ROLE, user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenTtl)))
                .signWith(key)
                .compact();
    }

    /** Returns the principal if the token is a well-formed, correctly signed, unexpired access token. */
    public Optional<AuthUser> parseAccessToken(String token) {
        return parse(token, TYPE_ACCESS).map(claims -> new AuthUser(
                Long.valueOf(claims.getSubject()),
                claims.get(CLAIM_EMAIL, String.class),
                Role.valueOf(claims.get(CLAIM_ROLE, String.class))));
    }

    public String issueAdmissionToken(Long userId, Long eventId, Instant expiresAt) {
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .claim(CLAIM_TYPE, TYPE_ADMISSION)
                .claim(CLAIM_EVENT, eventId)
                .issuedAt(Date.from(clock.instant()))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
    }

    /** True if the token is a valid, unexpired admission pass for exactly this fan and event. */
    public boolean isValidAdmission(String token, Long userId, Long eventId) {
        return token != null && parse(token, TYPE_ADMISSION)
                .filter(c -> userId.toString().equals(c.getSubject()))
                .filter(c -> eventId.equals(c.get(CLAIM_EVENT, Long.class)))
                .isPresent();
    }

    public Duration accessTokenTtl() {
        return accessTokenTtl;
    }

    private Optional<Claims> parse(String token, String expectedType) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(ISSUER)
                    .require(CLAIM_TYPE, expectedType)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(claims);
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
