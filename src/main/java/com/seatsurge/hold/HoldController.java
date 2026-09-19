package com.seatsurge.hold;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.seatsurge.auth.AuthUser;
import com.seatsurge.hold.dto.HoldDtos.HoldRequest;
import com.seatsurge.hold.dto.HoldDtos.HoldResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@PreAuthorize("hasRole('FAN')")
@Tag(name = "Seat holds", description = "Reserve seats for a limited time while checking out")
public class HoldController {

    private final HoldService holdService;

    @PostMapping("/events/{eventId}/holds")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Hold seats (all-or-nothing). 409 SEATS_UNAVAILABLE if any seat is taken")
    public HoldResponse create(@AuthenticationPrincipal AuthUser user, @PathVariable Long eventId,
            @Valid @RequestBody HoldRequest request) {
        return holdService.createHold(eventId, user, request.seatIds());
    }

    @GetMapping("/holds")
    @Operation(summary = "List my active holds")
    public List<HoldResponse> listActive(@AuthenticationPrincipal AuthUser user) {
        return holdService.listActive(user);
    }

    @GetMapping("/holds/{holdId}")
    @Operation(summary = "Get a hold with its seats, total price and remaining time")
    public HoldResponse get(@AuthenticationPrincipal AuthUser user, @PathVariable Long holdId) {
        return holdService.get(holdId, user);
    }

    @DeleteMapping("/holds/{holdId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Release a hold; its seats go straight back on sale")
    public void release(@AuthenticationPrincipal AuthUser user, @PathVariable Long holdId) {
        holdService.release(holdId, user);
    }
}
