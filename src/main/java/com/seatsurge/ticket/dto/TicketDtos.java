package com.seatsurge.ticket.dto;

import java.time.Instant;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class TicketDtos {

    private TicketDtos() {
    }

    public record TransferRequest(@NotBlank @Email String recipientEmail) {
    }

    public record CheckInRequest(
            @NotNull Long eventId,
            /** Raw QR content ("SEATSURGE:&lt;uuid&gt;") or the bare ticket code. */
            @NotBlank String code) {
    }

    public record CheckInResponse(String result, Long ticketId, String section, String row, Integer seat,
            Instant checkedInAt) {
    }
}
