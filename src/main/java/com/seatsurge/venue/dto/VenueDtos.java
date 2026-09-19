package com.seatsurge.venue.dto;

import java.util.List;

import com.seatsurge.venue.Venue;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class VenueDtos {

    private VenueDtos() {
    }

    public record VenueRequest(
            @NotBlank @Size(max = 150) String name,
            @NotBlank @Size(max = 255) String address,
            @NotBlank @Size(max = 100) String city) {
    }

    /** A section is laid out as rows; each row gets seats numbered 1..seatCount. */
    public record SectionRequest(
            @NotBlank @Size(max = 100) String name,
            @NotEmpty @Size(max = 200) List<@Valid RowRequest> rows) {
    }

    public record RowRequest(
            @NotBlank @Size(max = 10) @Pattern(regexp = "[A-Za-z0-9-]+") String label,
            @Min(1) @Max(500) int seatCount) {
    }

    public record VenueResponse(Long id, String name, String address, String city) {

        public static VenueResponse from(Venue venue) {
            return new VenueResponse(venue.getId(), venue.getName(), venue.getAddress(), venue.getCity());
        }
    }

    public record VenueDetailResponse(Long id, String name, String address, String city, long capacity,
            List<SectionResponse> sections) {
    }

    public record SectionResponse(Long id, String name, long seatCount, List<RowResponse> rows) {
    }

    public record RowResponse(String label, long seatCount) {
    }
}
