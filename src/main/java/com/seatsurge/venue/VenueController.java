package com.seatsurge.venue;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.seatsurge.auth.AuthUser;
import com.seatsurge.common.web.PageResponse;
import com.seatsurge.venue.dto.VenueDtos.SectionRequest;
import com.seatsurge.venue.dto.VenueDtos.VenueDetailResponse;
import com.seatsurge.venue.dto.VenueDtos.VenueRequest;
import com.seatsurge.venue.dto.VenueDtos.VenueResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/venues")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ORGANIZER', 'ADMIN')")
@Tag(name = "Venues", description = "Organizer-managed venues and their seating layout")
public class VenueController {

    private final VenueService venueService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a venue")
    public VenueDetailResponse create(@AuthenticationPrincipal AuthUser user,
            @Valid @RequestBody VenueRequest request) {
        return venueService.create(user, request);
    }

    @GetMapping
    @Operation(summary = "List my venues")
    public PageResponse<VenueResponse> listMine(@AuthenticationPrincipal AuthUser user,
            @ParameterObject @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC)
            Pageable pageable) {
        return venueService.listMine(user, pageable);
    }

    @GetMapping("/{venueId}")
    @Operation(summary = "Get a venue with its sections, rows and capacity")
    public VenueDetailResponse get(@AuthenticationPrincipal AuthUser user, @PathVariable Long venueId) {
        return venueService.get(venueId, user);
    }

    @PutMapping("/{venueId}")
    @Operation(summary = "Update venue details")
    public VenueDetailResponse update(@AuthenticationPrincipal AuthUser user, @PathVariable Long venueId,
            @Valid @RequestBody VenueRequest request) {
        return venueService.update(venueId, user, request);
    }

    @PostMapping("/{venueId}/sections")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a section; seats are generated per row (1..seatCount)")
    public VenueDetailResponse addSection(@AuthenticationPrincipal AuthUser user, @PathVariable Long venueId,
            @Valid @RequestBody SectionRequest request) {
        return venueService.addSection(venueId, user, request);
    }

    @DeleteMapping("/{venueId}/sections/{sectionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a section (only while no event uses the venue)")
    public void deleteSection(@AuthenticationPrincipal AuthUser user, @PathVariable Long venueId,
            @PathVariable Long sectionId) {
        venueService.deleteSection(venueId, sectionId, user);
    }
}
