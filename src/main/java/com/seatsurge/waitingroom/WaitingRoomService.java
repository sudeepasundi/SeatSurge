package com.seatsurge.waitingroom;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.seatsurge.auth.AuthUser;
import com.seatsurge.auth.JwtService;
import com.seatsurge.common.config.SeatSurgeProperties;
import com.seatsurge.common.exception.ApiException;
import com.seatsurge.common.exception.ConflictException;
import com.seatsurge.common.exception.NotFoundException;
import com.seatsurge.event.Event;
import com.seatsurge.event.EventRepository;
import com.seatsurge.event.EventStatus;
import com.seatsurge.event.SalePhase;

/**
 * Virtual waiting room for high-demand drops.
 *
 * <p>Joining hands out a queue number (1, 2, 3, ...) from a Redis counter; a Lua script makes joining atomic
 * and idempotent, so a fan keeps their number however often they retry. Nothing else is stored: whether a
 * number is admitted is a pure function of time,
 *
 * <pre>  allowance(now) = rate + floor(rate * minutesSince(saleStartsAt))   admitted  &lt;=&gt;  number &lt;= allowance</pre>
 *
 * so there is no gatekeeper job, and every app instance gives the same answer. Admitted fans receive a
 * short-lived signed admission token that the hold endpoint verifies without touching Redis.
 */
@Service
public class WaitingRoomService {

    private static final String KEY_PREFIX = "seatsurge:wr:";

    /** KEYS: counter, members hash. ARGV: userId, expire-at (epoch seconds). Returns the fan's number. */
    private static final RedisScript<Long> JOIN = new DefaultRedisScript<>("""
            local existing = redis.call('HGET', KEYS[2], ARGV[1])
            if existing then
                return tonumber(existing)
            end
            local number = redis.call('INCR', KEYS[1])
            redis.call('HSET', KEYS[2], ARGV[1], number)
            redis.call('EXPIREAT', KEYS[1], ARGV[2])
            redis.call('EXPIREAT', KEYS[2], ARGV[2])
            return number
            """, Long.class);

    private final EventRepository eventRepository;
    private final StringRedisTemplate redis;
    private final JwtService jwtService;
    private final Clock clock;
    private final Duration admissionTokenTtl;

    public WaitingRoomService(EventRepository eventRepository, StringRedisTemplate redis, JwtService jwtService,
            Clock clock, SeatSurgeProperties properties) {
        this.eventRepository = eventRepository;
        this.redis = redis;
        this.jwtService = jwtService;
        this.clock = clock;
        this.admissionTokenTtl = properties.waitingRoom().admissionTokenTtl();
    }

    @Transactional(readOnly = true)
    public QueueStatus join(Long eventId, AuthUser user) {
        Event event = loadQueueableEvent(eventId);
        // Keys live until one day after the show, then Redis cleans them up.
        long expireAt = event.getStartsAt().plus(Duration.ofDays(1)).getEpochSecond();
        Long number = redisCall(() -> redis.execute(JOIN, List.of(counterKey(eventId), membersKey(eventId)),
                user.id().toString(), String.valueOf(expireAt)));
        return status(event, user, number);
    }

    @Transactional(readOnly = true)
    public QueueStatus status(Long eventId, AuthUser user) {
        Event event = loadQueueableEvent(eventId);
        Object number = redisCall(() -> redis.opsForHash().get(membersKey(eventId), user.id().toString()));
        if (number == null) {
            throw new NotFoundException("Queue entry for event", eventId);
        }
        return status(event, user, Long.parseLong(number.toString()));
    }

    /** Leaving gives up the place; the number is not reused (numbers only ever increase). */
    public void leave(Long eventId, AuthUser user) {
        redisCall(() -> redis.opsForHash().delete(membersKey(eventId), user.id().toString()));
    }

    /** Organizer view: how many fans joined, and how many of them are admitted by now. */
    public QueueSnapshot snapshot(Event event) {
        if (!event.isWaitingRoomEnabled()) {
            return new QueueSnapshot(0, 0);
        }
        try {
            String counter = redis.opsForValue().get(counterKey(event.getId()));
            long joined = counter == null ? 0 : Long.parseLong(counter);
            return new QueueSnapshot(joined, Math.min(joined, allowance(event, clock.instant())));
        } catch (DataAccessException e) {
            return new QueueSnapshot(-1, -1);
        }
    }

    public record QueueSnapshot(long joined, long admitted) {
    }

    // ---------- internals ----------

    private QueueStatus status(Event event, AuthUser user, long number) {
        Instant now = clock.instant();
        long allowance = allowance(event, now);
        SalePhase phase = event.salePhase(now);

        if (number <= allowance && phase == SalePhase.ON_SALE) {
            Instant expiresAt = now.plus(admissionTokenTtl);
            return new QueueStatus(event.getId(), phase, number, true, 0, 0,
                    jwtService.issueAdmissionToken(user.id(), event.getId(), expiresAt), expiresAt);
        }
        long ahead = Math.max(0, number - allowance - 1);
        long rate = event.getAdmissionRatePerMinute();
        long waitSeconds = (long) Math.ceil((number - allowance) * 60.0 / rate);
        if (phase == SalePhase.UPCOMING) {
            // Before the sale: wait until it opens, plus the time for the people in front.
            waitSeconds = Duration.between(now, event.getSaleStartsAt()).toSeconds()
                    + (long) Math.ceil(Math.max(0, number - rate) * 60.0 / rate);
        }
        return new QueueStatus(event.getId(), phase, number, false, ahead, Math.max(0, waitSeconds), null, null);
    }

    /** Numbers admitted so far: one minute's worth at sale start, then {@code rate} more per minute. */
    static long allowance(Event event, Instant now) {
        if (now.isBefore(event.getSaleStartsAt())) {
            return 0;
        }
        long rate = event.getAdmissionRatePerMinute();
        long elapsedMillis = Duration.between(event.getSaleStartsAt(), now).toMillis();
        return rate + elapsedMillis * rate / 60_000;
    }

    private Event loadQueueableEvent(Long eventId) {
        Event event = eventRepository.findById(eventId).orElseThrow(() -> new NotFoundException("Event", eventId));
        if (event.getStatus() != EventStatus.PUBLISHED) {
            throw new NotFoundException("Event", eventId);
        }
        if (!event.isWaitingRoomEnabled()) {
            throw new ConflictException("NO_WAITING_ROOM", "This event has no waiting room; hold seats directly");
        }
        if (event.salePhase(clock.instant()) == SalePhase.CLOSED) {
            throw new ConflictException("EVENT_NOT_ON_SALE", "Tickets for this event are not on sale");
        }
        return event;
    }

    /** The queue lives only in Redis: without it we cannot hand out fair places, so fail clearly. */
    private static <T> T redisCall(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (DataAccessException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WAITING_ROOM_UNAVAILABLE",
                    "The waiting room is temporarily unavailable; please retry");
        }
    }

    private static String counterKey(Long eventId) {
        return KEY_PREFIX + eventId + ":seq";
    }

    private static String membersKey(Long eventId) {
        return KEY_PREFIX + eventId + ":members";
    }
}
