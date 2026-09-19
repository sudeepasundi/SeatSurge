package com.seatsurge.common.ratelimit;

import org.springframework.http.HttpStatus;

import com.seatsurge.common.exception.ApiException;

import lombok.Getter;

@Getter
public class RateLimitedException extends ApiException {

    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                "Too many requests; retry in " + retryAfterSeconds + " second(s)");
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
