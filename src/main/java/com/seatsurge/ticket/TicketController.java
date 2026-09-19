package com.seatsurge.ticket;

import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.seatsurge.auth.AuthUser;
import com.seatsurge.ticket.dto.TicketDtos.CheckInRequest;
import com.seatsurge.ticket.dto.TicketDtos.CheckInResponse;
import com.seatsurge.ticket.dto.TicketDtos.TransferRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Tickets & gate")
public class TicketController {

    private final TicketService ticketService;

    @GetMapping("/me/tickets")
    @PreAuthorize("hasRole('FAN')")
    @Operation(summary = "My tickets, soonest event first")
    public List<MyTicket> myTickets(@AuthenticationPrincipal AuthUser user) {
        return ticketService.myTickets(user);
    }

    @GetMapping("/tickets/{ticketId}")
    @PreAuthorize("hasAnyRole('FAN', 'ADMIN')")
    @Operation(summary = "Get one of my tickets")
    public MyTicket get(@AuthenticationPrincipal AuthUser user, @PathVariable Long ticketId) {
        return ticketService.get(ticketId, user);
    }

    @GetMapping(value = "/tickets/{ticketId}/qr")
    @PreAuthorize("hasRole('FAN')")
    @Operation(summary = "QR code (PNG) to show at the gate")
    public ResponseEntity<byte[]> qr(@AuthenticationPrincipal AuthUser user, @PathVariable Long ticketId) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore()) // a ticket QR is a bearer credential
                .body(ticketService.qrPng(ticketId, user));
    }

    @PostMapping("/tickets/{ticketId}/transfer")
    @PreAuthorize("hasRole('FAN')")
    @Operation(summary = "Transfer a ticket to another fan (the ticket code is rotated)")
    public MyTicket transfer(@AuthenticationPrincipal AuthUser user, @PathVariable Long ticketId,
            @Valid @RequestBody TransferRequest request) {
        return ticketService.transfer(ticketId, user, request.recipientEmail());
    }

    @PostMapping("/gate/check-in")
    @PreAuthorize("hasAnyRole('GATE_STAFF', 'ORGANIZER', 'ADMIN')")
    @Operation(summary = "Scan a ticket at the gate: 200 ADMITTED, or 409 ALREADY_CHECKED_IN / WRONG_EVENT / "
            + "TICKET_CANCELLED, 404 unknown code")
    public CheckInResponse checkIn(@AuthenticationPrincipal AuthUser staff, @Valid @RequestBody CheckInRequest request) {
        return ticketService.checkIn(request.eventId(), request.code(), staff);
    }
}
