package com.seatsurge.hold;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.seatsurge.auth.AuthUser;
import com.seatsurge.common.config.SeatSurgeProperties;
import com.seatsurge.common.exception.BadRequestException;
import com.seatsurge.common.exception.ConflictException;
import com.seatsurge.common.exception.NotFoundException;
import com.seatsurge.event.Event;
import com.seatsurge.event.EventRepository;
import com.seatsurge.event.SalePhase;
import com.seatsurge.hold.dto.HoldDtos.HoldResponse;
import com.seatsurge.seat.EventSeat;
import com.seatsurge.seat.EventSeatRepository;
import com.seatsurge.seat.SeatStatus;
import com.seatsurge.user.UserRepository;

/**
 * Seat holds: the anti-oversell core.
 *
 * <ol>
 *   <li><b>Redis fast path</b> - {@code SET NX} per seat rejects contended requests without touching Postgres.</li>
 *   <li><b>Postgres source of truth</b> - seats flip AVAILABLE -> HELD in one transaction guarded by
 *       {@code @Version}; if two transactions race, one fails with an optimistic-lock error.</li>
 *   <li><b>Expiry</b> - a sweeper returns seats of timed-out holds to the pool.</li>
 * </ol>
 *
 * Public methods manage transactions explicitly (TransactionTemplate) so Redis locks can be released
 * after a commit or rollback, outside the database transaction.
 */
@Service
public class HoldService {

    private static final Logger log = LoggerFactory.getLogger(HoldService.class);
    private static final int SWEEP_BATCH_SIZE = 100;

    private final HoldRepository holdRepository;
    private final EventSeatRepository eventSeatRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final SeatLockService seatLocks;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration holdTtl;
    private final int maxSeatsPerHold;

    public HoldService(HoldRepository holdRepository, EventSeatRepository eventSeatRepository,
            EventRepository eventRepository, UserRepository userRepository, SeatLockService seatLocks,
            PlatformTransactionManager transactionManager, Clock clock, SeatSurgeProperties properties) {
        this.holdRepository = holdRepository;
        this.eventSeatRepository = eventSeatRepository;
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.seatLocks = seatLocks;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.holdTtl = properties.hold().ttl();
        this.maxSeatsPerHold = properties.hold().maxSeatsPerHold();
    }

    public HoldResponse createHold(Long eventId, AuthUser user, List<Long> requestedSeatIds) {
        List<Long> seatIds = requestedSeatIds.stream().distinct().sorted().toList();
        if (seatIds.size() != requestedSeatIds.size()) {
            throw new BadRequestException("DUPLICATE_SEATS", "The same seat was requested more than once");
        }
        if (seatIds.size() > maxSeatsPerHold) {
            throw new BadRequestException("TOO_MANY_SEATS", "At most " + maxSeatsPerHold + " seats per hold");
        }

        String lockToken = UUID.randomUUID().toString();
        if (!seatLocks.tryLockAll(seatIds, lockToken, holdTtl)) {
            throw seatsUnavailable();
        }
        try {
            Long holdId = tx.execute(status -> reserve(eventId, user, seatIds, lockToken));
            return view(holdId);
        } catch (RuntimeException e) {
            seatLocks.unlockAll(seatIds, lockToken);
            throw translate(e);
        }
    }

    public HoldResponse get(Long holdId, AuthUser user) {
        Hold hold = loadOwned(holdId, user);
        return view(hold.getId());
    }

    public List<HoldResponse> listActive(AuthUser user) {
        return holdRepository.findByUserIdAndStatusOrderByExpiresAt(user.id(), HoldStatus.ACTIVE).stream()
                .map(h -> view(h.getId()))
                .toList();
    }

    public void release(Long holdId, AuthUser user) {
        Hold hold = loadOwned(holdId, user);
        List<Long> seatIds = tx.execute(status -> {
            if (holdRepository.transitionFromActive(holdId, HoldStatus.RELEASED, clock.instant()) == 0) {
                throw new ConflictException("HOLD_NOT_ACTIVE", "Only an active hold can be released");
            }
            List<Long> ids = eventSeatRepository.findIdsByHoldId(holdId);
            eventSeatRepository.releaseHeldSeats(holdId);
            return ids;
        });
        seatLocks.unlockAll(seatIds, hold.getLockToken());
    }

    /**
     * Releases every active hold of an event (used when the event is cancelled). Joins the caller's
     * transaction; Redis locks are released after it commits. A checkout still open for one of these holds
     * will find the hold gone when its payment arrives and be refunded automatically.
     *
     * @return number of holds released
     */
    public int releaseAllForEvent(Long eventId) {
        return tx.execute(status -> {
            int released = 0;
            for (Hold hold : holdRepository.findByEventIdAndStatus(eventId, HoldStatus.ACTIVE)) {
                String lockToken = hold.getLockToken();
                if (holdRepository.transitionFromActive(hold.getId(), HoldStatus.RELEASED, clock.instant()) == 1) {
                    List<Long> seatIds = eventSeatRepository.findIdsByHoldId(hold.getId());
                    eventSeatRepository.releaseHeldSeats(hold.getId());
                    seatLocks.unlockAllAfterCommit(seatIds, lockToken);
                    released++;
                }
            }
            return released;
        });
    }

    /**
     * Expires due holds one by one, each in its own short transaction. The conditional update makes this
     * safe to run on several instances at once: only one of them wins each hold.
     *
     * @return number of holds expired
     */
    public int expireDueHolds() {
        List<Long> dueIds = holdRepository.findExpiredActiveIds(clock.instant(), PageRequest.of(0, SWEEP_BATCH_SIZE));
        int expired = 0;
        for (Long holdId : dueIds) {
            try {
                String lockToken = holdRepository.findById(holdId).map(Hold::getLockToken).orElse(null);
                List<Long> seatIds = tx.execute(status -> {
                    if (holdRepository.expireIfDue(holdId, clock.instant()) == 0) {
                        return List.<Long>of();
                    }
                    List<Long> ids = eventSeatRepository.findIdsByHoldId(holdId);
                    eventSeatRepository.releaseHeldSeats(holdId);
                    return ids;
                });
                if (!seatIds.isEmpty()) {
                    seatLocks.unlockAll(seatIds, lockToken);
                    expired++;
                }
            } catch (RuntimeException e) {
                log.error("Failed to expire hold {}", holdId, e);
            }
        }
        return expired;
    }

    // ---------- internals ----------

    /** Runs inside a transaction. Any conflict surfaces as an exception, and the caller releases the Redis locks. */
    private Long reserve(Long eventId, AuthUser user, List<Long> seatIds, String lockToken) {
        Instant now = clock.instant();
        Event event = eventRepository.findById(eventId).orElseThrow(() -> new NotFoundException("Event", eventId));
        if (event.salePhase(now) != SalePhase.ON_SALE) {
            throw new ConflictException("EVENT_NOT_ON_SALE", "Tickets for this event are not on sale");
        }
        if (holdRepository.existsByUserIdAndEventIdAndStatus(user.id(), eventId, HoldStatus.ACTIVE)) {
            throw new ConflictException("ACTIVE_HOLD_EXISTS",
                    "You already hold seats for this event; complete or release that hold first");
        }
        long purchased = holdRepository.countPurchasedSeats(user.id(), eventId);
        if (purchased + seatIds.size() > event.getMaxTicketsPerUser()) {
            throw new ConflictException("TICKET_LIMIT_EXCEEDED", "This event allows at most "
                    + event.getMaxTicketsPerUser() + " tickets per fan (you already have " + purchased + ")");
        }

        List<EventSeat> seats = eventSeatRepository.findForEvent(eventId, seatIds);
        if (seats.size() != seatIds.size()) {
            var unknown = new HashSet<>(seatIds);
            seats.forEach(s -> unknown.remove(s.getId()));
            throw new BadRequestException("UNKNOWN_SEAT", "Seats " + unknown + " do not belong to this event");
        }
        if (seats.stream().anyMatch(s -> s.getStatus() != SeatStatus.AVAILABLE)) {
            throw seatsUnavailable();
        }

        Hold hold = holdRepository.save(new Hold(userRepository.getReferenceById(user.id()), event,
                now.plus(holdTtl), lockToken));
        for (EventSeat seat : seats) {
            seat.setStatus(SeatStatus.HELD);
            seat.setHoldId(hold.getId());
        }
        // Flush inside the transaction so a version conflict is raised here, not at an opaque commit.
        eventSeatRepository.flush();
        return hold.getId();
    }

    private Hold loadOwned(Long holdId, AuthUser user) {
        Hold hold = holdRepository.findById(holdId).orElseThrow(() -> new NotFoundException("Hold", holdId));
        user.requireCanManage(hold.userId(), "holds");
        return hold;
    }

    private HoldResponse view(Long holdId) {
        Hold hold = holdRepository.findById(holdId).orElseThrow(() -> new NotFoundException("Hold", holdId));
        List<HeldSeatView> seats = hold.getStatus() == HoldStatus.ACTIVE || hold.getStatus() == HoldStatus.CONVERTED
                ? eventSeatRepository.findHeldSeatViews(holdId)
                : List.of();
        long total = seats.stream().mapToLong(HeldSeatView::priceCents).sum();
        String currency = seats.isEmpty() ? null : seats.getFirst().currency();
        long remaining = hold.getStatus() == HoldStatus.ACTIVE
                ? Math.max(0, Duration.between(clock.instant(), hold.getExpiresAt()).toSeconds())
                : 0;
        return new HoldResponse(hold.getId(), hold.eventId(), hold.getStatus(), hold.getExpiresAt(), remaining,
                seats, total, currency);
    }

    private static RuntimeException translate(RuntimeException e) {
        if (e instanceof OptimisticLockingFailureException) {
            return seatsUnavailable(); // lost the race in Postgres
        }
        if (e instanceof DataIntegrityViolationException) {
            // uq_holds_one_active_per_user_event: the same fan raced themselves
            return new ConflictException("ACTIVE_HOLD_EXISTS", "You already hold seats for this event");
        }
        return e;
    }

    private static ConflictException seatsUnavailable() {
        return new ConflictException("SEATS_UNAVAILABLE", "One or more of the selected seats are no longer available");
    }
}
