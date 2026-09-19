package com.seatsurge.ticket;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.seatsurge.auth.AuthUser;
import com.seatsurge.common.exception.BadRequestException;
import com.seatsurge.common.exception.ConflictException;
import com.seatsurge.common.exception.ForbiddenException;
import com.seatsurge.common.exception.NotFoundException;
import com.seatsurge.event.Event;
import com.seatsurge.event.EventRepository;
import com.seatsurge.event.EventStatus;
import com.seatsurge.ticket.dto.TicketDtos.CheckInResponse;
import com.seatsurge.user.Role;
import com.seatsurge.user.User;
import com.seatsurge.user.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TicketService {

    /** Prefix inside the QR code, so scanners can tell SeatSurge tickets from arbitrary QR codes. */
    public static final String QR_PREFIX = "SEATSURGE:";

    private final TicketRepository ticketRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final QrCodeRenderer qrCodeRenderer;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<MyTicket> myTickets(AuthUser user) {
        return ticketRepository.findMyTickets(user.id());
    }

    @Transactional(readOnly = true)
    public MyTicket get(Long ticketId, AuthUser user) {
        MyTicket ticket = ticketRepository.findMyTicket(ticketId)
                .orElseThrow(() -> new NotFoundException("Ticket", ticketId));
        user.requireCanManage(ticket.ownerId(), "tickets");
        return ticket;
    }

    @Transactional(readOnly = true)
    public byte[] qrPng(Long ticketId, AuthUser user) {
        MyTicket ticket = get(ticketId, user);
        if (ticket.status() != TicketStatus.VALID) {
            throw new ConflictException("TICKET_CANCELLED", "This ticket is no longer valid");
        }
        return qrCodeRenderer.png(QR_PREFIX + ticket.code());
    }

    /**
     * Gives a ticket to another fan. The code is rotated in the same statement, so the previous owner's
     * QR code (or a screenshot of it) is dead the moment the transfer commits.
     */
    @Transactional
    public MyTicket transfer(Long ticketId, AuthUser user, String recipientEmail) {
        MyTicket ticket = get(ticketId, user);
        if (ticket.status() != TicketStatus.VALID || ticket.checkedInAt() != null) {
            throw new ConflictException("TICKET_NOT_TRANSFERABLE", "Only unused, valid tickets can be transferred");
        }
        if (!ticket.eventStartsAt().isAfter(clock.instant())) {
            throw new ConflictException("TICKET_NOT_TRANSFERABLE", "The event has already started");
        }
        User recipient = userRepository.findByEmail(recipientEmail.trim().toLowerCase(Locale.ROOT))
                .filter(User::isEnabled)
                .filter(u -> u.getRole() == Role.FAN)
                .orElseThrow(() -> new NotFoundException("Recipient", recipientEmail));
        if (recipient.getId().equals(ticket.ownerId())) {
            throw new BadRequestException("SELF_TRANSFER", "You already own this ticket");
        }
        if (ticketRepository.transfer(ticketId, ticket.ownerId(), recipient.getId(), UUID.randomUUID()) == 0) {
            throw new ConflictException("TICKET_NOT_TRANSFERABLE", "The ticket changed while transferring; try again");
        }
        return ticketRepository.findMyTicket(ticketId).orElseThrow();
    }

    /**
     * Admits a ticket at the gate. Success is decided by a single conditional UPDATE; only when it matches
     * nothing do we look the ticket up again to explain why it was rejected.
     */
    @Transactional
    public CheckInResponse checkIn(Long eventId, String scannedCode, AuthUser staff) {
        Event event = eventRepository.findById(eventId).orElseThrow(() -> new NotFoundException("Event", eventId));
        if (staff.role() == Role.ORGANIZER && !staff.canManage(event.organizerId())) {
            throw new ForbiddenException("NOT_OWNER", "Organizers can only check in tickets for their own events");
        }
        if (event.getStatus() == EventStatus.CANCELLED) {
            throw new ConflictException("EVENT_CANCELLED", "This event was cancelled");
        }
        UUID code = parseCode(scannedCode);
        Instant now = clock.instant();

        if (ticketRepository.checkIn(code, eventId, staff.id(), now) == 1) {
            Ticket ticket = ticketRepository.findByCode(code).orElseThrow();
            var seat = ticket.getEventSeat().getVenueSeat();
            return new CheckInResponse("ADMITTED", ticket.getId(), seat.getSection().getName(), seat.getRowLabel(),
                    seat.getSeatNumber(), now);
        }

        Ticket ticket = ticketRepository.findByCode(code)
                .orElseThrow(() -> new NotFoundException("Ticket", "with this code"));
        if (!ticket.getEvent().getId().equals(eventId)) {
            throw new ConflictException("WRONG_EVENT", "This ticket is for a different event");
        }
        if (ticket.getStatus() != TicketStatus.VALID) {
            throw new ConflictException("TICKET_CANCELLED", "This ticket was cancelled");
        }
        throw new ConflictException("ALREADY_CHECKED_IN", "Ticket was already used at " + ticket.getCheckedInAt());
    }

    private static UUID parseCode(String scanned) {
        String raw = scanned.trim();
        if (raw.startsWith(QR_PREFIX)) {
            raw = raw.substring(QR_PREFIX.length());
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("INVALID_TICKET_CODE", "Not a SeatSurge ticket code");
        }
    }
}
