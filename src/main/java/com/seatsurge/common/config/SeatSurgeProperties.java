package com.seatsurge.common.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "seatsurge")
public record SeatSurgeProperties(Jwt jwt, Stripe stripe, Hold hold, Outbox outbox) {

    public record Jwt(String secret, Duration accessTokenTtl, Duration refreshTokenTtl) {
    }

    /**
     * @param checkoutTtl how long a Stripe Checkout session stays open (Stripe's minimum is 30 minutes)
     */
    public record Stripe(String secretKey, String webhookSecret, String successUrl, String cancelUrl,
            Duration checkoutTtl) {
    }

    /**
     * @param ttl             how long seats stay reserved while the fan checks out
     * @param maxSeatsPerHold upper bound on seats in a single hold request
     * @param sweepInterval   how often expired holds are released
     * @param redisFastPath   use Redis seat locks to reject contended requests before touching Postgres
     */
    public record Hold(Duration ttl, int maxSeatsPerHold, Duration sweepInterval, boolean redisFastPath) {
    }

    /**
     * @param pollInterval how often the publisher looks for due events
     * @param batchSize    events claimed per poll
     * @param maxAttempts  attempts before an event is parked as FAILED
     * @param lease        how long a claimed event is reserved for one worker before others may retry it
     */
    public record Outbox(Duration pollInterval, int batchSize, int maxAttempts, Duration lease) {
    }
}
