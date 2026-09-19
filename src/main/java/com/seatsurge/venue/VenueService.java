package com.seatsurge.venue;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.seatsurge.auth.AuthUser;
import com.seatsurge.common.exception.BadRequestException;
import com.seatsurge.common.exception.ConflictException;
import com.seatsurge.common.exception.NotFoundException;
import com.seatsurge.common.web.PageResponse;
import com.seatsurge.event.EventRepository;
import com.seatsurge.user.UserRepository;
import com.seatsurge.venue.dto.VenueDtos.RowRequest;
import com.seatsurge.venue.dto.VenueDtos.RowResponse;
import com.seatsurge.venue.dto.VenueDtos.SectionRequest;
import com.seatsurge.venue.dto.VenueDtos.SectionResponse;
import com.seatsurge.venue.dto.VenueDtos.VenueDetailResponse;
import com.seatsurge.venue.dto.VenueDtos.VenueRequest;
import com.seatsurge.venue.dto.VenueDtos.VenueResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class VenueService {

    static final int MAX_SEATS_PER_SECTION = 10_000;

    private final VenueRepository venueRepository;
    private final SectionRepository sectionRepository;
    private final VenueSeatRepository venueSeatRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;

    @Transactional
    public VenueDetailResponse create(AuthUser user, VenueRequest request) {
        Venue venue = venueRepository.save(new Venue(userRepository.getReferenceById(user.id()),
                request.name().trim(), request.address().trim(), request.city().trim()));
        return toDetail(venue);
    }

    @Transactional(readOnly = true)
    public PageResponse<VenueResponse> listMine(AuthUser user, Pageable pageable) {
        return PageResponse.of(venueRepository.findByOrganizerId(user.id(), pageable), VenueResponse::from);
    }

    @Transactional(readOnly = true)
    public VenueDetailResponse get(Long venueId, AuthUser user) {
        return toDetail(loadManaged(venueId, user));
    }

    @Transactional
    public VenueDetailResponse update(Long venueId, AuthUser user, VenueRequest request) {
        Venue venue = loadManaged(venueId, user);
        venue.setName(request.name().trim());
        venue.setAddress(request.address().trim());
        venue.setCity(request.city().trim());
        return toDetail(venue);
    }

    @Transactional
    public VenueDetailResponse addSection(Long venueId, AuthUser user, SectionRequest request) {
        Venue venue = loadManaged(venueId, user);
        requireLayoutEditable(venueId);

        String name = request.name().trim();
        if (sectionRepository.existsByVenueIdAndNameIgnoreCase(venueId, name)) {
            throw new ConflictException("SECTION_EXISTS", "Section '" + name + "' already exists in this venue");
        }
        Set<String> labels = new HashSet<>();
        int totalSeats = 0;
        for (RowRequest row : request.rows()) {
            if (!labels.add(row.label().toUpperCase(Locale.ROOT))) {
                throw new BadRequestException("DUPLICATE_ROW", "Row '" + row.label() + "' appears more than once");
            }
            totalSeats += row.seatCount();
        }
        if (totalSeats > MAX_SEATS_PER_SECTION) {
            throw new BadRequestException("SECTION_TOO_LARGE",
                    "A section can have at most " + MAX_SEATS_PER_SECTION + " seats");
        }

        Section section = sectionRepository.save(new Section(venue, name));
        for (RowRequest row : request.rows()) {
            venueSeatRepository.insertRow(section.getId(), row.label().toUpperCase(Locale.ROOT), row.seatCount());
        }
        return toDetail(venue);
    }

    @Transactional
    public void deleteSection(Long venueId, Long sectionId, AuthUser user) {
        loadManaged(venueId, user);
        requireLayoutEditable(venueId);
        Section section = sectionRepository.findByIdAndVenueId(sectionId, venueId)
                .orElseThrow(() -> new NotFoundException("Section", sectionId));
        sectionRepository.delete(section); // venue_seats are removed by ON DELETE CASCADE
    }

    private Venue loadManaged(Long venueId, AuthUser user) {
        Venue venue = venueRepository.findById(venueId).orElseThrow(() -> new NotFoundException("Venue", venueId));
        user.requireCanManage(venue.organizerId(), "venues");
        return venue;
    }

    /** Once an event references the layout, changing it would silently change tickets already on sale. */
    private void requireLayoutEditable(Long venueId) {
        if (eventRepository.existsByVenueId(venueId)) {
            throw new ConflictException("VENUE_IN_USE", "The seating layout cannot change once events use this venue");
        }
    }

    private VenueDetailResponse toDetail(Venue venue) {
        Map<Long, List<SectionRowCount>> rowsBySection = venueSeatRepository.countRowsByVenue(venue.getId())
                .stream().collect(Collectors.groupingBy(SectionRowCount::sectionId));

        List<SectionResponse> sections = sectionRepository.findByVenueIdOrderByName(venue.getId()).stream()
                .map(section -> {
                    List<RowResponse> rows = rowsBySection.getOrDefault(section.getId(), List.of()).stream()
                            .map(r -> new RowResponse(r.rowLabel(), r.seatCount()))
                            .toList();
                    long seatCount = rows.stream().mapToLong(RowResponse::seatCount).sum();
                    return new SectionResponse(section.getId(), section.getName(), seatCount, rows);
                })
                .toList();
        long capacity = sections.stream().mapToLong(SectionResponse::seatCount).sum();
        return new VenueDetailResponse(venue.getId(), venue.getName(), venue.getAddress(), venue.getCity(),
                capacity, sections);
    }
}
