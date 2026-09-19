package com.seatsurge.order.dto;

import java.time.Instant;
import java.util.List;

import com.seatsurge.order.OrderStatus;
import com.seatsurge.ticket.TicketView;

public final class OrderDtos {

    private OrderDtos() {
    }

    public record CheckoutResponse(Long orderId, Long holdId, OrderStatus status, long amountCents, String currency,
            String checkoutUrl, Instant expiresAt) {
    }

    public record OrderSummary(Long id, Long eventId, String eventTitle, OrderStatus status, long amountCents,
            String currency, Instant createdAt) {
    }

    public record OrderDetail(Long id, Long eventId, String eventTitle, Long holdId, OrderStatus status,
            long amountCents, String currency, Instant createdAt, List<TicketView> tickets) {
    }
}
