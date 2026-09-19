package com.seatsurge.common.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "seatsurge")
public record SeatSurgeProperties(Jwt jwt, Stripe stripe, Hold hold) {

    public record Jwt(String secret, Duration accessTokenTtl, Duration refreshTokenTtl) {
    }

    public record Stripe(String secretKey, String webhookSecret, String successUrl, String cancelUrl) {
    }

    public record Hold(Duration ttl, int maxSeatsPerHold) {
    }
}
