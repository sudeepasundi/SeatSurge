package com.seatsurge.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.seatsurge.auth.AuthUser;
import com.seatsurge.common.exception.ApiException;
import com.seatsurge.common.outbox.OutboxPublisher;
import com.seatsurge.support.IntegrationTest;
import com.seatsurge.support.TestStubsConfiguration.FakePaymentGateway;
import com.seatsurge.user.Role;
import com.seatsurge.user.UserRepository;

class TicketIntegrationTest extends IntegrationTest {

    @Autowired
    private TicketService ticketService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private FakePaymentGateway stripe;

    private String organizer;
    private TestEvent event;
    private String fan;
    private long orderId;

    @BeforeEach
    void setUp() throws Exception {
        organizer = tokenFor("ORGANIZER");
        event = createPublishedEvent(organizer, 10, 4, true);
        fan = tokenFor("FAN");
        orderId = purchase(fan, event.eventId(), event.seatIds().get(0), event.seatIds().get(1));
    }

    @Test
    void fanSeesTicketsAndGetsAScannableQrCode() throws Exception {
        getAs(fan, "/api/v1/me/tickets")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].eventTitle").value("Flash Sale Night"))
                .andExpect(jsonPath("$[0].section").value("Main"))
                .andExpect(jsonPath("$[0].seat").value(1))
                .andExpect(jsonPath("$[0].status").value("VALID"))
                .andExpect(jsonPath("$[0].ownerId").doesNotExist());

        long ticketId = firstTicketId(fan);
        String code = read(getAs(fan, "/api/v1/tickets/{id}", ticketId), "$.code");

        byte[] png = getAs(fan, "/api/v1/tickets/{id}/qr", ticketId)
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(decodeQr(png)).isEqualTo(TicketService.QR_PREFIX + code);

        String stranger = tokenFor("FAN");
        getAs(stranger, "/api/v1/tickets/{id}", ticketId).andExpect(status().isForbidden());
        getAs(stranger, "/api/v1/tickets/{id}/qr", ticketId).andExpect(status().isForbidden());
    }

    @Test
    void gateCheckInAdmitsEachTicketOnce() throws Exception {
        String gate = tokenForStaff("GATE_STAFF");
        List<String> codes = read(getAs(fan, "/api/v1/me/tickets"), "$[*].code");

        checkIn(gate, event.eventId(), TicketService.QR_PREFIX + codes.get(0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ADMITTED"))
                .andExpect(jsonPath("$.section").value("Main"))
                .andExpect(jsonPath("$.row").value("A"))
                .andExpect(jsonPath("$.seat").value(1));

        checkIn(gate, event.eventId(), codes.get(0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_CHECKED_IN"));

        TestEvent otherEvent = createPublishedEvent(organizer, 2, 2, true);
        checkIn(gate, otherEvent.eventId(), codes.get(1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WRONG_EVENT"));
        checkIn(gate, event.eventId(), UUID.randomUUID().toString()).andExpect(status().isNotFound());
        checkIn(gate, event.eventId(), "not-a-ticket")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TICKET_CODE"));

        // Fans cannot scan; organizers only for their own events
        checkIn(fan, event.eventId(), codes.get(1)).andExpect(status().isForbidden());
        checkIn(tokenFor("ORGANIZER"), event.eventId(), codes.get(1))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_OWNER"));
        checkIn(organizer, event.eventId(), codes.get(1)).andExpect(status().isOk());

        getAs(fan, "/api/v1/me/tickets").andExpect(jsonPath("$[0].checkedInAt", not(nullValue())));
        getAs(organizer, "/api/v1/events/{id}/stats", event.eventId())
                .andExpect(jsonPath("$.soldSeats").value(2))
                .andExpect(jsonPath("$.availableSeats").value(8))
                .andExpect(jsonPath("$.paidOrders").value(1))
                .andExpect(jsonPath("$.revenueCents").value(10000))
                .andExpect(jsonPath("$.checkedIn").value(2));
    }

    @Test
    void fiftyGatesScanningTheSameTicketAdmitItExactlyOnce() throws Exception {
        String code = read(getAs(fan, "/api/v1/me/tickets"), "$[0].code");
        var staffUser = userRepository.findByEmail(ADMIN_EMAIL).orElseThrow();
        AuthUser gate = new AuthUser(staffUser.getId(), staffUser.getEmail(), Role.ADMIN);

        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 50; i++) {
                results.add(pool.submit(() -> {
                    startGun.await();
                    try {
                        return ticketService.checkIn(event.eventId(), code, gate).result();
                    } catch (ApiException e) {
                        return e.getCode();
                    }
                }));
            }
            startGun.countDown();
        }
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : results) {
            outcomes.add(f.get());
        }
        assertThat(outcomes).containsOnlyOnce("ADMITTED");
        assertThat(outcomes.stream().filter("ALREADY_CHECKED_IN"::equals)).hasSize(49);
    }

    @Test
    void transferMovesTicketAndKillsTheOldQrCode() throws Exception {
        String friend = tokenFor("FAN");
        String friendEmail = read(getAs(friend, "/api/v1/users/me"), "$.email");
        long ticketId = firstTicketId(fan);
        String oldCode = read(getAs(fan, "/api/v1/tickets/{id}", ticketId), "$.code");

        postAs(fan, "/api/v1/tickets/{id}/transfer", "{\"recipientEmail\":\"%s\"}".formatted(friendEmail.toUpperCase()),
                ticketId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", not(oldCode)));

        getAs(fan, "/api/v1/me/tickets").andExpect(jsonPath("$", hasSize(1)));
        getAs(friend, "/api/v1/me/tickets").andExpect(jsonPath("$", hasSize(1)));
        getAs(fan, "/api/v1/tickets/{id}", ticketId).andExpect(status().isForbidden());

        // A screenshot of the old QR code no longer gets anyone in
        checkIn(tokenForStaff("GATE_STAFF"), event.eventId(), oldCode).andExpect(status().isNotFound());

        postAs(friend, "/api/v1/tickets/{id}/transfer", "{\"recipientEmail\":\"%s\"}".formatted(friendEmail), ticketId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SELF_TRANSFER"));
        postAs(friend, "/api/v1/tickets/{id}/transfer", "{\"recipientEmail\":\"nobody@example.com\"}", ticketId)
                .andExpect(status().isNotFound());

        // Used tickets cannot be transferred
        long otherTicket = firstTicketId(fan);
        String otherCode = read(getAs(fan, "/api/v1/tickets/{id}", otherTicket), "$.code");
        checkIn(organizer, event.eventId(), otherCode).andExpect(status().isOk());
        postAs(fan, "/api/v1/tickets/{id}/transfer", "{\"recipientEmail\":\"%s\"}".formatted(friendEmail), otherTicket)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_TRANSFERABLE"));
    }

    @Test
    void cancellingAnEventRefundsBuyersVoidsTicketsAndReleasesHolds() throws Exception {
        String holder = tokenFor("FAN");
        long holdId = ((Number) read(postAs(holder, "/api/v1/events/{id}/holds",
                "{\"seatIds\":[%d]}".formatted(event.seatIds().get(5)), event.eventId())
                .andExpect(status().isCreated()), "$.id")).longValue();
        String code = read(getAs(fan, "/api/v1/me/tickets"), "$[0].code");

        postAs(organizer, "/api/v1/events/{id}/cancel", null, event.eventId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        getAs(fan, "/api/v1/orders/{id}", orderId)
                .andExpect(jsonPath("$.status").value("REFUND_PENDING"))
                .andExpect(jsonPath("$.tickets[*].status", org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("CANCELLED"))));
        getAs(holder, "/api/v1/holds/{id}", holdId).andExpect(jsonPath("$.status").value("RELEASED"));
        getAs(fan, "/api/v1/tickets/{id}/qr", firstTicketId(fan))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_CANCELLED"));
        checkIn(organizer, event.eventId(), code)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_CANCELLED"));

        outboxPublisher.processBatch();
        assertThat(stripe.refundedPaymentIntents).contains("pi_" + orderId);
        getAs(fan, "/api/v1/orders/{id}", orderId).andExpect(jsonPath("$.status").value("REFUNDED"));
    }

    @Test
    void adminManagesUsers() throws Exception {
        String admin = adminToken();
        getAs(fan, "/api/v1/admin/users").andExpect(status().isForbidden());

        postAs(admin, "/api/v1/admin/users", """
                {"email":"gate-%s@example.com","password":"gate-pass-123","fullName":"Gate 1","role":"GATE_STAFF"}"""
                .formatted(UUID.randomUUID()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("GATE_STAFF"));
        getAs(admin, "/api/v1/admin/users?role=GATE_STAFF")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].role", org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("GATE_STAFF"))));

        // Disabling an account blocks login
        String email = "blocked-" + UUID.randomUUID() + "@example.com";
        postAs(null, "/api/v1/auth/register", """
                {"email":"%s","password":"s3cure-pass","fullName":"Soon Blocked"}""".formatted(email))
                .andExpect(status().isCreated());
        long userId = userRepository.findByEmail(email).orElseThrow().getId();
        putAs(admin, "/api/v1/admin/users/{id}/status", "{\"enabled\":false}", userId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        postAs(null, "/api/v1/auth/login", """
                {"email":"%s","password":"s3cure-pass"}""".formatted(email))
                .andExpect(status().isUnauthorized());
    }

    // ---------- helpers ----------

    private org.springframework.test.web.servlet.ResultActions checkIn(String token, long eventId, String code)
            throws Exception {
        return postAs(token, "/api/v1/gate/check-in", "{\"eventId\":%d,\"code\":\"%s\"}".formatted(eventId, code));
    }

    private long firstTicketId(String token) throws Exception {
        return ((Number) read(getAs(token, "/api/v1/me/tickets"), "$[0].id")).longValue();
    }

    private static String decodeQr(byte[] png) throws Exception {
        var image = ImageIO.read(new ByteArrayInputStream(png));
        var bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
        return new MultiFormatReader().decode(bitmap).getText();
    }
}
