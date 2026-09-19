package com.seatsurge.common.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "seatsurge")
public record SeatSurgeProperties(Jwt jwt, Stripe stripe, Hold hold) {

    public record Jwt(String secret, Duration accessTokenTtl, Duration refreshTokenTtl) {
    }

    public record Stripe(String secretKey, String webhookSecret, String successUrl, String cancelUrl) {
    }

    /**
     * @param ttl             how long seats stay reserved while the fan checks out
     * @param maxSeatsPerHold upper bound on seats in a single hold request
     * @param sweepInterval   how often expired holds are released
     * @param redisFastPath   use Redis seat locks to reject contended requests before touching Postgres
     */
    public record Hold(Duration ttl, int maxSeatsPerHold, Duration sweepInterval, boolean redisFastPath) {
    }
}
