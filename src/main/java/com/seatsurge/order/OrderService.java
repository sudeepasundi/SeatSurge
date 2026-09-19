package com.seatsurge.order;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.seatsurge.auth.AuthUser;
import com.seatsurge.common.config.SeatSurgeProperties;
import com.seatsurge.common.exception.ConflictException;
import com.seatsurge.common.exception.NotFoundException;
import com.seatsurge.common.outbox.OutboxWriter;
import com.seatsurge.common.web.PageResponse;
import com.seatsurge.event.Event;
import com.seatsurge.event.EventStatus;
import com.seatsurge.hold.HeldSeatView;
import com.seatsurge.hold.Hold;
import com.seatsurge.hold.HoldRepository;
import com.seatsurge.hold.HoldStatus;
import com.seatsurge.hold.SeatLockService;
import com.seatsurge.order.dto.OrderDtos.CheckoutResponse;
import com.seatsurge.order.dto.OrderDtos.OrderDetail;
import com.seatsurge.order.dto.OrderDtos.OrderSummary;
import com.seatsurge.payment.PaidOrderRef;
import com.seatsurge.payment.Payment;
import com.seatsurge.payment.PaymentGateway;
import com.seatsurge.payment.PaymentGateway.CheckoutSession;
import com.seatsurge.payment.PaymentGateway.CheckoutSessionRequest;
import com.seatsurge.payment.PaymentGateway.LineItem;
import com.seatsurge.payment.PaymentRepository;
import com.seatsurge.payment.PaymentStatus;
import com.seatsurge.seat.EventSeatRepository;
import com.seatsurge.ticket.Ticket;
import com.seatsurge.ticket.TicketRepository;
import com.seatsurge.user.User;
import com.seatsurge.user.UserRepository;

/**
 * Order lifecycle: checkout (hold -> order + Stripe session), then webhook-driven fulfillment
 * (order PAID, seats SOLD, tickets issued) or expiry, and refunds for payments that arrive too late.
 */
@Service
public class OrderService {

    public static final String ORDER_PAID = "ORDER_PAID";
    public static final String REFUND_REQUESTED = "REFUND_REQUESTED";

    /** Hold outlives the Stripe session a little, so a payment finished at the last second still finds its seats. */
    private static final Duration HOLD_GRACE = Duration.ofMinutes(2);

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final HoldRepository holdRepository;
    private final EventSeatRepository eventSeatRepository;
    private final TicketRepository ticketRepository;
    private final UserRepository userRepository;
    private final PaymentGateway paymentGateway;
    private final OutboxWriter outbox;
    private final SeatLockService seatLocks;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration checkoutTtl;

    public OrderService(OrderRepository orderRepository, PaymentRepository paymentRepository,
            HoldRepository holdRepository, EventSeatRepository eventSeatRepository, TicketRepository ticketRepository,
            UserRepository userRepository, PaymentGateway paymentGateway, OutboxWriter outbox, SeatLockService seatLocks,
            PlatformTransactionManager transactionManager, Clock clock, SeatSurgeProperties properties) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.holdRepository = holdRepository;
        this.eventSeatRepository = eventSeatRepository;
        this.ticketRepository = ticketRepository;
        this.userRepository = userRepository;
        this.paymentGateway = paymentGateway;
        this.outbox = outbox;
        this.seatLocks = seatLocks;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.checkoutTtl = properties.stripe().checkoutTtl();
    }

    // ---------- checkout ----------

    /**
     * Three steps so the Stripe call never runs inside a database transaction:
     * (1) create order + payment and extend the hold, (2) call Stripe, (3) attach the session.
     * Repeating the call for the same hold returns the same session (orders.hold_id is unique and the
     * Stripe request carries an idempotency key derived from the order id).
     */
    public CheckoutResponse checkout(Long holdId, AuthUser user) {
        PreparedCheckout prepared = tx.execute(status -> prepareCheckout(holdId, user));
        if (prepared.request() != null) {
            CheckoutSession session = paymentGateway.createCheckoutSession(prepared.request());
            tx.executeWithoutResult(status -> {
                Payment payment = paymentRepository.findById(prepared.paymentId()).orElseThrow();
                payment.setStripeSessionId(session.id());
                payment.setCheckoutUrl(session.url());
                payment.setStatus(PaymentStatus.PENDING);
            });
        }
        return tx.execute(status -> checkoutResponse(prepared.orderId()));
    }

    private PreparedCheckout prepareCheckout(Long holdId, AuthUser user) {
        Hold hold = holdRepository.findById(holdId).orElseThrow(() -> new NotFoundException("Hold", holdId));
        user.requireCanManage(hold.userId(), "holds");
        Instant now = clock.instant();

        var existing = orderRepository.findByHoldId(holdId);
        if (existing.isPresent()) {
            Order order = existing.get();
            if (order.getStatus() != OrderStatus.PENDING) {
                throw new ConflictException("ORDER_ALREADY_" + order.getStatus(),
                        "This hold was already checked out (order " + order.getId() + " is " + order.getStatus() + ")");
            }
            Payment payment = paymentRepository.findByOrderId(order.getId()).orElseThrow();
            if (payment.getCheckoutUrl() != null) {
                return new PreparedCheckout(order.getId(), payment.getId(), null);
            }
            // A previous attempt failed to reach Stripe: retry with the same idempotency key.
            return new PreparedCheckout(order.getId(), payment.getId(),
                    sessionRequest(order, hold, user, hold.getExpiresAt().minus(HOLD_GRACE)));
        }

        if (hold.getStatus() != HoldStatus.ACTIVE || !hold.getExpiresAt().isAfter(now)) {
            throw new ConflictException("HOLD_NOT_ACTIVE", "This hold has expired or was released");
        }
        Event event = hold.getEvent();
        if (event.getStatus() != EventStatus.PUBLISHED) {
            throw new ConflictException("EVENT_NOT_ON_SALE", "Tickets for this event are not on sale");
        }
        List<HeldSeatView> seats = eventSeatRepository.findHeldSeatViews(holdId);
        long amount = seats.stream().mapToLong(HeldSeatView::priceCents).sum();

        Order order = orderRepository.save(new Order(userRepository.getReferenceById(user.id()), event, hold,
                amount, seats.getFirst().currency()));
        Payment payment = paymentRepository.save(new Payment(order));
        Instant sessionExpiresAt = now.plus(checkoutTtl);
        hold.extendTo(sessionExpiresAt.plus(HOLD_GRACE));
        return new PreparedCheckout(order.getId(), payment.getId(), sessionRequest(order, hold, user, sessionExpiresAt));
    }

    private CheckoutSessionRequest sessionRequest(Order order, Hold hold, AuthUser user, Instant expiresAt) {
        String title = hold.getEvent().getTitle();
        List<LineItem> items = eventSeatRepository.findHeldSeatViews(hold.getId()).stream()
                .map(s -> new LineItem("%s - %s, Row %s, Seat %d".formatted(title, s.section(), s.row(), s.number()),
                        s.priceCents()))
                .toList();
        return new CheckoutSessionRequest(order.getId(), hold.getId(), user.email(), order.getCurrency(), items,
                expiresAt, "checkout-order-" + order.getId());
    }

    private CheckoutResponse checkoutResponse(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        return new CheckoutResponse(order.getId(), order.holdId(), order.getStatus(), order.getAmountCents(),
                order.getCurrency(), payment.getCheckoutUrl(), order.getHold().getExpiresAt().minus(HOLD_GRACE));
    }

    // ---------- webhook-driven transitions (run inside the webhook transaction) ----------

    /**
     * Payment confirmed. If the hold is still ACTIVE it is converted: seats SOLD, tickets issued, and a
     * confirmation email queued, all in one transaction. If the hold was already lost (expired/released),
     * the money is returned: the order goes to REFUND_PENDING and a refund is queued in the outbox.
     */
    /*
     * Note on ordering: the bulk updates below flush and then clear the persistence context, so entities are
     * (re)loaded after one bulk update and modified before the next, whose auto-flush persists them.
     */
    @Transactional
    public void fulfill(Long orderId, String sessionId, String paymentIntentId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("Payment received for unknown order {}", orderId);
            return;
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            log.info("Order {} already {}, ignoring payment confirmation", orderId, order.getStatus());
            return;
        }
        Long holdId = order.holdId();
        Long ownerId = order.userId();
        Instant now = clock.instant();

        boolean converted = holdRepository.transitionFromActive(holdId, HoldStatus.CONVERTED, now) == 1;

        Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        payment.setStripePaymentIntentId(paymentIntentId);
        if (payment.getStripeSessionId() == null) {
            payment.setStripeSessionId(sessionId);
        }
        payment.setStatus(PaymentStatus.SUCCEEDED);

        if (!converted) {
            orderRepository.transition(orderId, OrderStatus.PENDING, OrderStatus.REFUND_PENDING, now);
            outbox.enqueue("order", orderId, REFUND_REQUESTED,
                    Map.of("orderId", orderId, "paymentIntentId", paymentIntentId, "reason", "HOLD_LOST"));
            log.warn("Order {} paid after its hold was lost; refund queued", orderId);
            return;
        }

        int sold = eventSeatRepository.markHeldSeatsSold(holdId);
        Order orderRef = orderRepository.getReferenceById(orderId);
        User owner = userRepository.getReferenceById(ownerId);
        List<Ticket> tickets = eventSeatRepository.findByHoldId(holdId).stream()
                .map(seat -> new Ticket(orderRef, seat.getEvent(), seat, owner))
                .toList();
        ticketRepository.saveAll(tickets);
        orderRepository.transition(orderId, OrderStatus.PENDING, OrderStatus.PAID, now);

        outbox.enqueue("order", orderId, ORDER_PAID, Map.of("orderId", orderId));
        log.info("Order {} paid: {} seat(s) sold, {} ticket(s) issued", orderId, sold, tickets.size());
    }

    /** Checkout session expired without payment: give the seats back right away. */
    @Transactional
    public void expireCheckout(Long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            return;
        }
        Long holdId = order.holdId();
        String lockToken = order.getHold().getLockToken();
        if (orderRepository.transition(orderId, OrderStatus.PENDING, OrderStatus.CANCELLED, clock.instant()) == 0) {
            return;
        }
        paymentRepository.findByOrderId(orderId).ifPresent(p -> p.setStatus(PaymentStatus.EXPIRED));
        if (holdRepository.transitionFromActive(holdId, HoldStatus.EXPIRED, clock.instant()) == 1) {
            List<Long> seatIds = eventSeatRepository.findIdsByHoldId(holdId);
            eventSeatRepository.releaseHeldSeats(holdId);
            seatLocks.unlockAllAfterCommit(seatIds, lockToken);
        }
    }

    /**
     * Event cancelled by the organizer: every paid order moves to REFUND_PENDING with a refund queued in the
     * outbox, and all tickets are voided, in the caller's transaction.
     *
     * @return number of orders queued for refund
     */
    @Transactional
    public int refundAllPaidOrders(Long eventId) {
        int queued = 0;
        for (PaidOrderRef paid : paymentRepository.findPaidOrders(eventId)) {
            if (orderRepository.transition(paid.orderId(), OrderStatus.PAID, OrderStatus.REFUND_PENDING,
                    clock.instant()) == 1) {
                outbox.enqueue("order", paid.orderId(), REFUND_REQUESTED, Map.of("orderId", paid.orderId(),
                        "paymentIntentId", paid.paymentIntentId(), "reason", "EVENT_CANCELLED"));
                queued++;
            }
        }
        ticketRepository.cancelAllForEvent(eventId);
        return queued;
    }

    /** Called by the refund outbox handler once Stripe accepted the refund. */
    @Transactional
    public void markRefunded(Long orderId) {
        if (orderRepository.transition(orderId, OrderStatus.REFUND_PENDING, OrderStatus.REFUNDED, clock.instant()) == 1) {
            paymentRepository.findByOrderId(orderId).ifPresent(p -> p.setStatus(PaymentStatus.REFUNDED));
        }
    }

    // ---------- queries ----------

    @Transactional(readOnly = true)
    public PageResponse<OrderSummary> listMine(AuthUser user, Pageable pageable) {
        return PageResponse.of(orderRepository.findByUserId(user.id(), pageable),
                o -> new OrderSummary(o.getId(), o.getEvent().getId(), o.getEvent().getTitle(), o.getStatus(),
                        o.getAmountCents(), o.getCurrency(), o.getCreatedAt()));
    }

    @Transactional(readOnly = true)
    public OrderDetail get(Long orderId, AuthUser user) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new NotFoundException("Order", orderId));
        user.requireCanManage(order.userId(), "orders");
        return new OrderDetail(order.getId(), order.getEvent().getId(), order.getEvent().getTitle(), order.holdId(),
                order.getStatus(), order.getAmountCents(), order.getCurrency(), order.getCreatedAt(),
                ticketRepository.findViewsByOrderId(orderId));
    }

    /** request == null means the Stripe session already exists. */
    private record PreparedCheckout(Long orderId, Long paymentId, CheckoutSessionRequest request) {
    }
}
