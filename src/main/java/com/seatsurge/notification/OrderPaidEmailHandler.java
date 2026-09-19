package com.seatsurge.notification;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.seatsurge.common.outbox.OutboxHandler;
import com.seatsurge.order.Order;
import com.seatsurge.order.OrderRepository;
import com.seatsurge.order.OrderService;
import com.seatsurge.ticket.TicketRepository;
import com.seatsurge.ticket.TicketView;
import com.seatsurge.user.UserRepository;

import tools.jackson.databind.JsonNode;

/** Sends the "your tickets" email after a successful payment. */
@Component
public class OrderPaidEmailHandler implements OutboxHandler {

    private final OrderRepository orderRepository;
    private final TicketRepository ticketRepository;
    private final UserRepository userRepository;
    private final TicketMailer mailer;
    private final TransactionTemplate readOnlyTx;

    public OrderPaidEmailHandler(OrderRepository orderRepository, TicketRepository ticketRepository,
            UserRepository userRepository, TicketMailer mailer, PlatformTransactionManager transactionManager) {
        this.orderRepository = orderRepository;
        this.ticketRepository = ticketRepository;
        this.userRepository = userRepository;
        this.mailer = mailer;
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
    }

    @Override
    public String eventType() {
        return OrderService.ORDER_PAID;
    }

    @Override
    public void handle(JsonNode payload) {
        long orderId = payload.path("orderId").asLong();
        Email email = readOnlyTx.execute(status -> compose(orderId));
        // Sent outside the transaction: no DB connection is held while talking to the mail server.
        mailer.send(email.to(), email.subject(), email.body());
    }

    private Email compose(long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        String to = userRepository.findById(order.userId()).orElseThrow().getEmail();
        String title = order.getEvent().getTitle();
        List<TicketView> tickets = ticketRepository.findViewsByOrderId(orderId);

        StringBuilder body = new StringBuilder()
                .append("Thanks for your order #").append(orderId).append(" - you're going to ").append(title).append("!\n\n")
                .append("Total paid: ").append(money(order.getAmountCents(), order.getCurrency())).append("\n\n")
                .append("Your tickets:\n");
        for (TicketView t : tickets) {
            body.append("  - Section ").append(t.section()).append(", Row ").append(t.row()).append(", Seat ")
                    .append(t.number()).append("  (ticket code ").append(t.code()).append(")\n");
        }
        body.append("\nShow the QR code of each ticket at the gate.\n");
        return new Email(to, "Your tickets for " + title, body.toString());
    }

    private static String money(long cents, String currency) {
        return String.format(Locale.ROOT, "%.2f %s", cents / 100.0, currency.toUpperCase(Locale.ROOT));
    }

    private record Email(String to, String subject, String body) {
    }
}
