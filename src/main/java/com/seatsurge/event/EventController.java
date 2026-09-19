package com.seatsurge.event;

import java.time.Instant;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.seatsurge.auth.AuthUser;
import com.seatsurge.common.web.PageResponse;
import com.seatsurge.event.dto.EventDtos.EventDetailResponse;
import com.seatsurge.event.dto.EventDtos.EventRequest;
import com.seatsurge.event.dto.EventDtos.EventSummary;
import com.seatsurge.event.dto.EventDtos.SeatMapResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
@Tag(name = "Events", description = "Public discovery plus organizer event lifecycle (draft -> published -> cancelled)")
public class EventController {

    private static final String ORGANIZER = "hasAnyRole('ORGANIZER', 'ADMIN')";

    private final EventService eventService;

    // ---------- public ----------

    @GetMapping
    @Operation(summary = "Search published upcoming events (public)")
    public PageResponse<EventSummary> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @ParameterObject @PageableDefault(size = 20, sort = "startsAt", direction = Sort.Direction.ASC)
            Pageable pageable) {
        return eventService.search(q, city, category, from, to, pageable);
    }

    @GetMapping("/{eventId}")
    @Operation(summary = "Event details with price tiers and live availability (public; drafts only for owner)")
    public EventDetailResponse get(@AuthenticationPrincipal AuthUser viewer, @PathVariable Long eventId) {
        return eventService.get(eventId, viewer);
    }

    @GetMapping("/{eventId}/seats")
    @Operation(summary = "Seat map: sections -> rows -> seats with live status (public; drafts only for owner)")
    public SeatMapResponse seatMap(@AuthenticationPrincipal AuthUser viewer, @PathVariable Long eventId) {
        return eventService.seatMap(eventId, viewer);
    }

    // ---------- organizer ----------

    @GetMapping("/mine")
    @PreAuthorize(ORGANIZER)
    @Operation(summary = "List my events in every status")
    public PageResponse<EventSummary> listMine(@AuthenticationPrincipal AuthUser user,
            @ParameterObject @PageableDefault(size = 20, sort = "startsAt", direction = Sort.Direction.ASC)
            Pageable pageable) {
        return eventService.listMine(user, pageable);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ORGANIZER)
    @Operation(summary = "Create a draft event; seats are generated from the venue layout per price tier")
    public EventDetailResponse create(@AuthenticationPrincipal AuthUser user,
            @Valid @RequestBody EventRequest request) {
        return eventService.create(user, request);
    }

    @PutMapping("/{eventId}")
    @PreAuthorize(ORGANIZER)
    @Operation(summary = "Replace a draft event, including its pricing")
    public EventDetailResponse update(@AuthenticationPrincipal AuthUser user, @PathVariable Long eventId,
            @Valid @RequestBody EventRequest request) {
        return eventService.update(eventId, user, request);
    }

    @DeleteMapping("/{eventId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(ORGANIZER)
    @Operation(summary = "Delete a draft event")
    public void delete(@AuthenticationPrincipal AuthUser user, @PathVariable Long eventId) {
        eventService.delete(eventId, user);
    }

    @PostMapping("/{eventId}/publish")
    @PreAuthorize(ORGANIZER)
    @Operation(summary = "Publish a draft: it becomes visible and goes on sale at saleStartsAt")
    public EventDetailResponse publish(@AuthenticationPrincipal AuthUser user, @PathVariable Long eventId) {
        return eventService.publish(eventId, user);
    }

    @PostMapping("/{eventId}/cancel")
    @PreAuthorize(ORGANIZER)
    @Operation(summary = "Cancel an event")
    public EventDetailResponse cancel(@AuthenticationPrincipal AuthUser user, @PathVariable Long eventId) {
        return eventService.cancel(eventId, user);
    }
}
