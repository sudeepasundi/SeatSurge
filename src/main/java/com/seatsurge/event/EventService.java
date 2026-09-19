package com.seatsurge.event;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.seatsurge.auth.AuthUser;
import com.seatsurge.common.exception.BadRequestException;
import com.seatsurge.common.exception.ConflictException;
import com.seatsurge.common.exception.NotFoundException;
import com.seatsurge.common.web.PageResponse;
import com.seatsurge.event.dto.EventDtos.EventDetailResponse;
import com.seatsurge.event.dto.EventDtos.EventRequest;
import com.seatsurge.event.dto.EventDtos.EventSummary;
import com.seatsurge.event.dto.EventDtos.PriceTierRequest;
import com.seatsurge.event.dto.EventDtos.SeatMapResponse;
import com.seatsurge.event.dto.EventDtos.SeatMapSection;
import com.seatsurge.event.dto.EventDtos.SeatRow;
import com.seatsurge.event.dto.EventDtos.SeatView;
import com.seatsurge.event.dto.EventDtos.TierResponse;
import com.seatsurge.hold.HoldService;
import com.seatsurge.order.OrderRepository;
import com.seatsurge.order.OrderService;
import com.seatsurge.order.OrderStatus;
import com.seatsurge.seat.EventSeatRepository;
import com.seatsurge.seat.SeatMapRow;
import com.seatsurge.seat.SeatStatus;
import com.seatsurge.ticket.TicketRepository;
import com.seatsurge.user.UserRepository;
import com.seatsurge.venue.Section;
import com.seatsurge.venue.SectionRepository;
import com.seatsurge.venue.Venue;
import com.seatsurge.venue.VenueRepository;
import com.seatsurge.venue.dto.VenueDtos.VenueResponse;
import com.seatsurge.waitingroom.WaitingRoomService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private static final int DEFAULT_MAX_TICKETS_PER_USER = 6;
    private static final String DEFAULT_CURRENCY = "usd";

    private final EventRepository eventRepository;
    private final PriceTierRepository priceTierRepository;
    private final EventSeatRepository eventSeatRepository;
    private final VenueRepository venueRepository;
    private final SectionRepository sectionRepository;
    private final UserRepository userRepository;
    private final HoldService holdService;
    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final TicketRepository ticketRepository;
    private final WaitingRoomService waitingRoomService;
    private final Clock clock;

    // ---------- organizer ----------

    @Transactional
    public EventDetailResponse create(AuthUser user, EventRequest request) {
        Event event = new Event(userRepository.getReferenceById(user.id()));
        applyDetails(event, request, user);
        eventRepository.save(event);
        createPricingAndSeats(event, request);
        return toDetail(event);
    }

    /** Full replace of a draft, including pricing: tiers and seats are rebuilt from the venue layout. */
    @Transactional
    public EventDetailResponse update(Long eventId, AuthUser user, EventRequest request) {
        Event event = loadManaged(eventId, user);
        requireStatus(event, EventStatus.DRAFT, "Only draft events can be edited");
        applyDetails(event, request, user);
        eventSeatRepository.deleteByEventId(eventId);
        priceTierRepository.deleteByEventId(eventId);
        createPricingAndSeats(event, request);
        return toDetail(event);
    }

    @Transactional
    public void delete(Long eventId, AuthUser user) {
        Event event = loadManaged(eventId, user);
        requireStatus(event, EventStatus.DRAFT, "Only draft events can be deleted; cancel it instead");
        eventRepository.delete(event); // price tiers and event seats cascade in the database
    }

    @Transactional
    public EventDetailResponse publish(Long eventId, AuthUser user) {
        Event event = loadManaged(eventId, user);
        requireStatus(event, EventStatus.DRAFT, "Only draft events can be published");
        if (!event.getStartsAt().isAfter(clock.instant())) {
            throw new BadRequestException("EVENT_IN_PAST", "Cannot publish an event that has already started");
        }
        if (eventSeatRepository.countByEventId(eventId) == 0) {
            throw new BadRequestException("NO_SEATS", "The event has no seats to sell");
        }
        event.setStatus(EventStatus.PUBLISHED);
        return toDetail(event);
    }

    /**
     * Cancels the event in one transaction: status CANCELLED, active holds released, every paid order
     * queued for refund through the outbox and all tickets voided. Either all of it happens or none.
     */
    @Transactional
    public EventDetailResponse cancel(Long eventId, AuthUser user) {
        Event event = loadManaged(eventId, user);
        if (event.getStatus() == EventStatus.CANCELLED) {
            throw new ConflictException("INVALID_EVENT_STATUS", "Event is already cancelled");
        }
        event.setStatus(EventStatus.CANCELLED);
        int holds = holdService.releaseAllForEvent(eventId);
        int refunds = orderService.refundAllPaidOrders(eventId);
        log.info("Event {} cancelled: {} hold(s) released, {} order(s) queued for refund", eventId, holds, refunds);
        // Bulk updates above cleared the persistence context, so reload before rendering.
        return toDetail(eventRepository.findById(eventId).orElseThrow());
    }

    @Transactional(readOnly = true)
    public EventStats stats(Long eventId, AuthUser user) {
        Event event = loadManaged(eventId, user);
        var queue = waitingRoomService.snapshot(event);
        String currency = priceTierRepository.findByEventIdOrderByPriceCentsDesc(eventId).stream()
                .map(PriceTier::getCurrency).findFirst().orElse(null);
        return new EventStats(eventId, event.getStatus(),
                eventSeatRepository.countByEventId(eventId),
                eventSeatRepository.countByEventIdAndStatus(eventId, SeatStatus.AVAILABLE),
                eventSeatRepository.countByEventIdAndStatus(eventId, SeatStatus.HELD),
                eventSeatRepository.countByEventIdAndStatus(eventId, SeatStatus.SOLD),
                orderRepository.countByEventIdAndStatus(eventId, OrderStatus.PAID),
                orderRepository.sumAmountByEventIdAndStatus(eventId, OrderStatus.PAID),
                currency,
                ticketRepository.countCheckedIn(eventId),
                queue.joined(), queue.admitted());
    }

    @Transactional(readOnly = true)
    public PageResponse<EventSummary> listMine(AuthUser user, Pageable pageable) {
        return toSummaries(eventRepository.findByOrganizerId(user.id(), pageable));
    }

    // ---------- public ----------

    @Transactional(readOnly = true)
    public PageResponse<EventSummary> search(String query, String city, String category, Instant from, Instant to,
            Pageable pageable) {
        var spec = EventSpecifications.publicSearch(query, city, category, from, to, clock.instant());
        return toSummaries(eventRepository.findAll(spec, pageable));
    }

    /** Drafts are only visible to their organizer (and admins); everyone else gets a 404. */
    @Transactional(readOnly = true)
    public EventDetailResponse get(Long eventId, AuthUser viewer) {
        return toDetail(loadVisible(eventId, viewer));
    }

    @Transactional(readOnly = true)
    public SeatMapResponse seatMap(Long eventId, AuthUser viewer) {
        Event event = loadVisible(eventId, viewer);
        Map<Long, List<SeatMapRow>> bySection = eventSeatRepository.findSeatMap(eventId).stream()
                .collect(Collectors.groupingBy(SeatMapRow::sectionId, LinkedHashMap::new, Collectors.toList()));

        long available = 0;
        List<SeatMapSection> sections = new ArrayList<>();
        for (List<SeatMapRow> seats : bySection.values()) {
            SeatMapRow first = seats.getFirst();
            Map<String, List<SeatView>> rows = new LinkedHashMap<>();
            for (SeatMapRow seat : seats) {
                rows.computeIfAbsent(seat.rowLabel(), k -> new ArrayList<>())
                        .add(new SeatView(seat.eventSeatId(), seat.seatNumber(), seat.status()));
                if (seat.status() == SeatStatus.AVAILABLE) {
                    available++;
                }
            }
            sections.add(new SeatMapSection(first.sectionId(), first.sectionName(), first.tierId(), first.tierName(),
                    first.priceCents(), rows.entrySet().stream()
                            .map(e -> new SeatRow(e.getKey(), e.getValue()))
                            .toList()));
        }
        return new SeatMapResponse(eventId, event.salePhase(clock.instant()), available, sections);
    }

    // ---------- internals ----------

    private void applyDetails(Event event, EventRequest request, AuthUser user) {
        if (!request.saleStartsAt().isBefore(request.startsAt())) {
            throw new BadRequestException("INVALID_SCHEDULE", "saleStartsAt must be before startsAt");
        }
        Venue venue = venueRepository.findById(request.venueId())
                .orElseThrow(() -> new NotFoundException("Venue", request.venueId()));
        user.requireCanManage(venue.organizerId(), "venues");

        event.setVenue(venue);
        event.setTitle(request.title().trim());
        event.setArtist(request.artist().trim());
        event.setDescription(request.description());
        event.setCategory(request.category() == null ? null : request.category().trim());
        event.setStartsAt(request.startsAt());
        event.setSaleStartsAt(request.saleStartsAt());
        event.setMaxTicketsPerUser(request.maxTicketsPerUser() == null
                ? DEFAULT_MAX_TICKETS_PER_USER : request.maxTicketsPerUser());
        event.setWaitingRoomEnabled(Boolean.TRUE.equals(request.waitingRoomEnabled()));
        if (request.admissionRatePerMinute() != null) {
            event.setAdmissionRatePerMinute(request.admissionRatePerMinute());
        }
    }

    private void createPricingAndSeats(Event event, EventRequest request) {
        Set<Long> venueSectionIds = sectionRepository.findByVenueIdOrderByName(event.getVenue().getId()).stream()
                .map(Section::getId).collect(Collectors.toSet());
        Set<String> tierNames = new HashSet<>();
        Set<Long> assignedSections = new HashSet<>();

        for (PriceTierRequest tier : request.priceTiers()) {
            if (!tierNames.add(tier.name().trim().toLowerCase(Locale.ROOT))) {
                throw new BadRequestException("DUPLICATE_TIER", "Price tier '" + tier.name() + "' is defined twice");
            }
            for (Long sectionId : tier.sectionIds()) {
                if (!venueSectionIds.contains(sectionId)) {
                    throw new BadRequestException("UNKNOWN_SECTION",
                            "Section " + sectionId + " does not belong to the event's venue");
                }
                if (!assignedSections.add(sectionId)) {
                    throw new BadRequestException("SECTION_PRICED_TWICE",
                            "Section " + sectionId + " is assigned to more than one price tier");
                }
            }
        }

        String currency = request.currency() == null ? DEFAULT_CURRENCY : request.currency();
        for (PriceTierRequest tier : request.priceTiers()) {
            PriceTier saved = priceTierRepository.save(
                    new PriceTier(event, tier.name().trim(), tier.priceCents(), currency));
            eventSeatRepository.insertForTier(event.getId(), saved.getId(), tier.sectionIds());
        }
    }

    private Event loadManaged(Long eventId, AuthUser user) {
        Event event = eventRepository.findById(eventId).orElseThrow(() -> new NotFoundException("Event", eventId));
        user.requireCanManage(event.organizerId(), "events");
        return event;
    }

    private Event loadVisible(Long eventId, AuthUser viewer) {
        Event event = eventRepository.findById(eventId).orElseThrow(() -> new NotFoundException("Event", eventId));
        if (event.getStatus() == EventStatus.DRAFT && (viewer == null || !viewer.canManage(event.organizerId()))) {
            throw new NotFoundException("Event", eventId);
        }
        return event;
    }

    private static void requireStatus(Event event, EventStatus expected, String message) {
        if (event.getStatus() != expected) {
            throw new ConflictException("INVALID_EVENT_STATUS", message + " (current status: " + event.getStatus() + ")");
        }
    }

    private EventDetailResponse toDetail(Event event) {
        Map<Long, TierAvailability> availability = eventSeatRepository.countByTier(event.getId()).stream()
                .collect(Collectors.toMap(TierAvailability::tierId, Function.identity()));
        List<TierResponse> tiers = priceTierRepository.findByEventIdOrderByPriceCentsDesc(event.getId()).stream()
                .map(t -> {
                    TierAvailability a = availability.get(t.getId());
                    return new TierResponse(t.getId(), t.getName(), t.getPriceCents(), t.getCurrency(),
                            a == null ? 0 : a.totalSeats(), a == null ? 0 : a.availableSeats());
                })
                .toList();
        long total = tiers.stream().mapToLong(TierResponse::totalSeats).sum();
        long available = tiers.stream().mapToLong(TierResponse::availableSeats).sum();

        return new EventDetailResponse(event.getId(), event.getTitle(), event.getArtist(), event.getDescription(),
                event.getCategory(), event.getStartsAt(), event.getSaleStartsAt(), event.getStatus(),
                event.salePhase(clock.instant()), event.getMaxTicketsPerUser(),
                event.isWaitingRoomEnabled(), event.getAdmissionRatePerMinute(), event.organizerId(),
                VenueResponse.from(event.getVenue()), total, available, tiers);
    }

    private PageResponse<EventSummary> toSummaries(Page<Event> page) {
        List<Long> ids = page.getContent().stream().map(Event::getId).toList();
        Map<Long, EventPrice> prices = ids.isEmpty() ? Map.of()
                : priceTierRepository.findMinPrices(ids).stream()
                        .collect(Collectors.toMap(EventPrice::eventId, Function.identity()));
        Instant now = clock.instant();
        return PageResponse.of(page, e -> {
            EventPrice price = prices.get(e.getId());
            return new EventSummary(e.getId(), e.getTitle(), e.getArtist(), e.getCategory(), e.getStartsAt(),
                    e.getSaleStartsAt(), e.getStatus(), e.salePhase(now), e.getVenue().getName(),
                    e.getVenue().getCity(), price == null ? null : price.minPriceCents(),
                    price == null ? null : price.currency());
        });
    }
}
