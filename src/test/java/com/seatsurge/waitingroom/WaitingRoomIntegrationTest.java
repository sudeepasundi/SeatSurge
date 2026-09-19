package com.seatsurge.waitingroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.seatsurge.auth.AuthUser;
import com.seatsurge.support.IntegrationTest;
import com.seatsurge.user.Role;
import com.seatsurge.user.User;
import com.seatsurge.user.UserRepository;

class WaitingRoomIntegrationTest extends IntegrationTest {

    private static final String WAITING_ROOM = "\"waitingRoomEnabled\":true,\"admissionRatePerMinute\":2";

    @Autowired
    private WaitingRoomService waitingRoomService;

    @Autowired
    private UserRepository userRepository;

    @Test
    void queueAdmitsFansAtTheConfiguredRateAndHoldsRequireTheAdmissionToken() throws Exception {
        String organizer = tokenFor("ORGANIZER");
        // Sale opens now; rate 2/min => numbers 1-2 are admitted immediately, number 3 after ~30s.
        TestEvent event = createPublishedEvent(organizer, 10, 4, Instant.now().truncatedTo(ChronoUnit.SECONDS),
                WAITING_ROOM);
        String alice = tokenFor("FAN");
        String bob = tokenFor("FAN");
        String carol = tokenFor("FAN");

        String aliceAdmission = read(join(alice, event.eventId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queueNumber").value(1))
                .andExpect(jsonPath("$.admitted").value(true))
                .andExpect(jsonPath("$.admissionToken").isNotEmpty()), "$.admissionToken");
        join(bob, event.eventId()).andExpect(jsonPath("$.queueNumber").value(2))
                .andExpect(jsonPath("$.admitted").value(true));
        join(carol, event.eventId())
                .andExpect(jsonPath("$.queueNumber").value(3))
                .andExpect(jsonPath("$.admitted").value(false))
                .andExpect(jsonPath("$.peopleAhead").value(0))
                .andExpect(jsonPath("$.estimatedWaitSeconds", allOf(greaterThan(0), lessThanOrEqualTo(30))))
                .andExpect(jsonPath("$.admissionToken").doesNotExist());

        // Joining again is idempotent: same place in line
        join(alice, event.eventId()).andExpect(jsonPath("$.queueNumber").value(1));
        getAs(carol, "/api/v1/events/{id}/queue", event.eventId()).andExpect(jsonPath("$.admitted").value(false));

        long seat = event.seatIds().get(0);
        // No token, someone else's token, or an access token used as admission: all refused
        hold(carol, event.eventId(), seat, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMISSION_REQUIRED"));
        hold(bob, event.eventId(), seat, aliceAdmission).andExpect(status().isForbidden());
        hold(alice, event.eventId(), seat, alice).andExpect(status().isForbidden());
        // ... and an admission token is not an access token either
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + aliceAdmission))
                .andExpect(status().isUnauthorized());

        // A token is only valid for the event it was issued for
        TestEvent otherDrop = createPublishedEvent(organizer, 5, 4, Instant.now().minusSeconds(5), WAITING_ROOM);
        hold(alice, otherDrop.eventId(), otherDrop.seatIds().get(0), aliceAdmission).andExpect(status().isForbidden());

        hold(alice, event.eventId(), seat, aliceAdmission).andExpect(status().isCreated());

        getAs(organizer, "/api/v1/events/{id}/stats", event.eventId())
                .andExpect(jsonPath("$.queueJoined").value(3))
                .andExpect(jsonPath("$.queueAdmitted").value(2));
    }

    @Test
    void fansCanQueueBeforeTheSaleOpens() throws Exception {
        TestEvent event = createPublishedEvent(tokenFor("ORGANIZER"), 5, 4, Instant.now().plus(1, ChronoUnit.HOURS),
                WAITING_ROOM);
        join(tokenFor("FAN"), event.eventId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salePhase").value("UPCOMING"))
                .andExpect(jsonPath("$.admitted").value(false))
                .andExpect(jsonPath("$.estimatedWaitSeconds", allOf(greaterThanOrEqualTo(3500), lessThanOrEqualTo(3600))));
    }

    @Test
    void eventsWithoutWaitingRoomRejectQueueing() throws Exception {
        TestEvent event = createPublishedEvent(tokenFor("ORGANIZER"), 5, 4, true);
        join(tokenFor("FAN"), event.eventId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_WAITING_ROOM"));
    }

    @Test
    void concurrentJoinsGetUniqueGaplessNumbers() throws Exception {
        TestEvent event = createPublishedEvent(tokenFor("ORGANIZER"), 5, 4, Instant.now().plus(1, ChronoUnit.HOURS),
                WAITING_ROOM);
        List<AuthUser> fans = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            User u = userRepository.save(new User("q-" + UUID.randomUUID() + "@example.com", "n/a", "Fan", Role.FAN));
            fans.add(new AuthUser(u.getId(), u.getEmail(), Role.FAN));
        }

        List<Long> numbers = raceJoins(event.eventId(), fans);
        assertThat(numbers).doesNotHaveDuplicates().hasSize(100);
        assertThat(numbers.stream().mapToLong(Long::longValue).max().orElseThrow()).isEqualTo(100);

        // Everyone retrying at once still keeps their original number
        assertThat(raceJoins(event.eventId(), fans)).containsExactlyElementsOf(numbers);
    }

    @Test
    void holdRequestsAreRateLimitedPerFan() throws Exception {
        TestEvent event = createPublishedEvent(tokenFor("ORGANIZER"), 5, 4, true);
        String spammer = tokenFor("FAN");
        for (int i = 0; i < 10; i++) {
            hold(spammer, event.eventId(), 999_999_000L + i, null).andExpect(status().isBadRequest());
        }
        hold(spammer, event.eventId(), event.seatIds().get(0), null)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().string("Retry-After", org.hamcrest.Matchers.matchesPattern("[1-9][0-9]?")));

        // Limits are per fan: others are unaffected
        hold(tokenFor("FAN"), event.eventId(), event.seatIds().get(0), null).andExpect(status().isCreated());
    }

    @Test
    void loginAttemptsAreRateLimitedPerAccount() throws Exception {
        String email = "target-" + UUID.randomUUID() + "@example.com";
        postAs(null, "/api/v1/auth/register", """
                {"email":"%s","password":"right-password","fullName":"Target"}""".formatted(email))
                .andExpect(status().isCreated());
        for (int i = 0; i < 10; i++) {
            attemptLogin(email, "wrong-password-" + i).andExpect(status().isUnauthorized());
        }
        // Even the right password is refused until the window slides
        attemptLogin(email, "right-password")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    // ---------- helpers ----------

    private ResultActions join(String token, long eventId) throws Exception {
        return postAs(token, "/api/v1/events/{id}/queue", null, eventId);
    }

    private ResultActions hold(String token, long eventId, long seatId, String admissionToken) throws Exception {
        var request = post("/api/v1/events/{id}/holds", eventId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seatIds\":[" + seatId + "]}");
        if (admissionToken != null) {
            request.header("X-Admission-Token", admissionToken);
        }
        return mockMvc.perform(request);
    }

    private ResultActions attemptLogin(String email, String password) throws Exception {
        return postAs(null, "/api/v1/auth/login", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password));
    }

    private List<Long> raceJoins(long eventId, List<AuthUser> fans) throws Exception {
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (AuthUser fan : fans) {
                futures.add(pool.submit(() -> {
                    startGun.await();
                    return waitingRoomService.join(eventId, fan).queueNumber();
                }));
            }
            startGun.countDown();
        }
        List<Long> numbers = new ArrayList<>();
        for (Future<Long> f : futures) {
            numbers.add(f.get());
        }
        return numbers;
    }
}
