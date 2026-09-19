package com.seatsurge.event.dto;

import java.time.Instant;
import java.util.List;

import com.seatsurge.event.EventStatus;
import com.seatsurge.event.SalePhase;
import com.seatsurge.seat.SeatStatus;
import com.seatsurge.venue.dto.VenueDtos.VenueResponse;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class EventDtos {

    private EventDtos() {
    }

    /** Create/replace a draft event. Each price tier covers one or more sections of the venue. */
    public record EventRequest(
            @NotNull Long venueId,
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 150) String artist,
            @Size(max = 5000) String description,
            @Size(max = 50) String category,
            @NotNull @Future Instant startsAt,
            @NotNull Instant saleStartsAt,
            @Min(1) @Max(10) Integer maxTicketsPerUser,
            @Schema(description = "Queue fans in a virtual waiting room before they can hold seats (default false)")
            Boolean waitingRoomEnabled,
            @Schema(description = "Fans admitted per minute once the sale opens (default 600)")
            @Min(1) @Max(100_000) Integer admissionRatePerMinute,
            @Schema(description = "ISO-4217 lowercase, defaults to usd") @Pattern(regexp = "[a-z]{3}") String currency,
            @NotEmpty @Size(max = 20) List<@Valid PriceTierRequest> priceTiers) {
    }

    public record PriceTierRequest(
            @NotBlank @Size(max = 50) String name,
            @NotNull @PositiveOrZero Long priceCents,
            @NotEmpty List<@NotNull Long> sectionIds) {
    }

    public record TierResponse(Long id, String name, long priceCents, String currency, long totalSeats,
            long availableSeats) {
    }

    public record EventDetailResponse(Long id, String title, String artist, String description, String category,
            Instant startsAt, Instant saleStartsAt, EventStatus status, SalePhase salePhase, int maxTicketsPerUser,
            boolean waitingRoomEnabled, int admissionRatePerMinute,
            Long organizerId, VenueResponse venue, long totalSeats, long availableSeats,
            List<TierResponse> priceTiers) {
    }

    public record EventSummary(Long id, String title, String artist, String category, Instant startsAt,
            Instant saleStartsAt, EventStatus status, SalePhase salePhase, String venueName, String city,
            Long minPriceCents, String currency) {
    }

    public record SeatMapResponse(Long eventId, SalePhase salePhase, long availableSeats,
            List<SeatMapSection> sections) {
    }

    public record SeatMapSection(Long sectionId, String name, Long tierId, String tierName, long priceCents,
            List<SeatRow> rows) {
    }

    public record SeatRow(String label, List<SeatView> seats) {
    }

    public record SeatView(Long id, int number, SeatStatus status) {
    }
}
