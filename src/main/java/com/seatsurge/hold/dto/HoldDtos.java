package com.seatsurge.hold.dto;

import java.time.Instant;
import java.util.List;

import com.seatsurge.hold.HeldSeatView;
import com.seatsurge.hold.HoldStatus;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public final class HoldDtos {

    private HoldDtos() {
    }

    public record HoldRequest(@NotEmpty List<@NotNull Long> seatIds) {
    }

    public record HoldResponse(Long id, Long eventId, HoldStatus status, Instant expiresAt, long secondsRemaining,
            List<HeldSeatView> seats, long totalCents, String currency) {
    }
}
