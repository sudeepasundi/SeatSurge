package com.seatsurge.waitingroom;

import java.time.Duration;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.seatsurge.auth.AuthUser;
import com.seatsurge.common.config.SeatSurgeProperties;
import com.seatsurge.common.ratelimit.RateLimiter;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/events/{eventId}/queue")
@PreAuthorize("hasRole('FAN')")
@Tag(name = "Waiting room", description = "Fair, paced admission for high-demand drops")
public class WaitingRoomController {

    private final WaitingRoomService waitingRoomService;
    private final RateLimiter rateLimiter;
    private final int joinsPerMinute;

    public WaitingRoomController(WaitingRoomService waitingRoomService, RateLimiter rateLimiter,
            SeatSurgeProperties properties) {
        this.waitingRoomService = waitingRoomService;
        this.rateLimiter = rateLimiter;
        this.joinsPerMinute = properties.rateLimit().queueJoinsPerMinute();
    }

    @PostMapping
    @Operation(summary = "Join the waiting room (idempotent: re-joining keeps your number)")
    public QueueStatus join(@AuthenticationPrincipal AuthUser user, @PathVariable Long eventId) {
        rateLimiter.check("queue-join:" + user.id(), joinsPerMinute, Duration.ofMinutes(1));
        return waitingRoomService.join(eventId, user);
    }

    @GetMapping
    @Operation(summary = "Poll my position; once admitted the response carries the admission token")
    public QueueStatus status(@AuthenticationPrincipal AuthUser user, @PathVariable Long eventId) {
        return waitingRoomService.status(eventId, user);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Leave the waiting room")
    public void leave(@AuthenticationPrincipal AuthUser user, @PathVariable Long eventId) {
        waitingRoomService.leave(eventId, user);
    }
}
