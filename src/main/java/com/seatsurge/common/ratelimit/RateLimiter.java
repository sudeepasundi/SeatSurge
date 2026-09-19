package com.seatsurge.common.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Sliding-window rate limiter on Redis (one sorted set of request timestamps per key). Unlike fixed
 * per-minute buckets, it cannot be gamed by bursting at a window boundary. The whole check-and-record
 * runs as one Lua script, so concurrent requests cannot both squeeze in under the limit.
 * Fails open: if Redis is down, requests are allowed (the database guarantees still apply).
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private static final String KEY_PREFIX = "seatsurge:rl:";

    /** Returns 0 if allowed (and records the hit), otherwise milliseconds until a slot frees up. */
    private static final RedisScript<Long> SLIDING_WINDOW = new DefaultRedisScript<>("""
            local key, now, window, limit, member = KEYS[1], tonumber(ARGV[1]), tonumber(ARGV[2]), tonumber(ARGV[3]), ARGV[4]
            redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window)
            if redis.call('ZCARD', key) < limit then
                redis.call('ZADD', key, now, member)
                redis.call('PEXPIRE', key, window)
                return 0
            end
            local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
            return math.max(1, tonumber(oldest[2]) + window - now)
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;

    public RateLimiter(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /** @throws RateLimitedException if {@code key} already made {@code limit} calls within {@code window} */
    public void check(String key, int limit, Duration window) {
        Long retryAfterMs;
        try {
            retryAfterMs = redis.execute(SLIDING_WINDOW, List.of(KEY_PREFIX + key),
                    String.valueOf(clock.millis()), String.valueOf(window.toMillis()), String.valueOf(limit),
                    UUID.randomUUID().toString());
        } catch (DataAccessException e) {
            log.warn("Rate limiter unavailable, allowing request: {}", e.getMessage());
            return;
        }
        if (retryAfterMs != null && retryAfterMs > 0) {
            throw new RateLimitedException(Math.max(1, (retryAfterMs + 999) / 1000));
        }
    }
}
