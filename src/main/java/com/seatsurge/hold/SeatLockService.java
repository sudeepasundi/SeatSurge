package com.seatsurge.hold;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import com.seatsurge.common.config.SeatSurgeProperties;

/**
 * Redis fast path in front of Postgres. Under a flash-sale stampede most requests are for seats that
 * someone else is already buying; a {@code SET NX} lets us reject those in about a millisecond, without
 * opening a database transaction. Postgres (optimistic locking) remains the source of truth, so if Redis
 * is down we degrade to database-only mode instead of failing.
 */
@Service
public class SeatLockService {

    private static final Logger log = LoggerFactory.getLogger(SeatLockService.class);
    private static final String KEY_PREFIX = "seatsurge:seat-lock:";

    /** Delete the key only if we still own it, so an expired-then-reacquired lock is never stolen. */
    private static final RedisScript<Long> COMPARE_AND_DELETE = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final boolean enabled;

    public SeatLockService(StringRedisTemplate redis, SeatSurgeProperties properties) {
        this.redis = redis;
        this.enabled = properties.hold().redisFastPath();
    }

    /**
     * Tries to lock every seat for {@code token}. All-or-nothing: if any seat is taken, the locks acquired
     * so far are released and false is returned.
     */
    public boolean tryLockAll(Collection<Long> eventSeatIds, String token, Duration ttl) {
        if (!enabled) {
            return true;
        }
        List<Long> acquired = new ArrayList<>(eventSeatIds.size());
        try {
            for (Long seatId : eventSeatIds) {
                if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(seatId), token, ttl))) {
                    unlockAll(acquired, token);
                    return false;
                }
                acquired.add(seatId);
            }
            return true;
        } catch (DataAccessException e) {
            log.warn("Redis unavailable, falling back to database-only seat locking: {}", e.getMessage());
            unlockAll(acquired, token);
            return true;
        }
    }

    public void unlockAll(Collection<Long> eventSeatIds, String token) {
        if (!enabled || token == null) {
            return;
        }
        try {
            for (Long seatId : eventSeatIds) {
                redis.execute(COMPARE_AND_DELETE, List.of(key(seatId)), token);
            }
        } catch (DataAccessException e) {
            // Keys carry a TTL equal to the hold duration, so they clean themselves up.
            log.warn("Could not release Redis seat locks: {}", e.getMessage());
        }
    }

    private static String key(Long eventSeatId) {
        return KEY_PREFIX + eventSeatId;
    }
}
