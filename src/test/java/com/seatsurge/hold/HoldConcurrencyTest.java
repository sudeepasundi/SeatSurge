package com.seatsurge.hold;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.seatsurge.auth.AuthUser;
import com.seatsurge.common.exception.ApiException;
import com.seatsurge.hold.dto.HoldDtos.HoldResponse;
import com.seatsurge.support.IntegrationTest;
import com.seatsurge.user.Role;
import com.seatsurge.user.User;
import com.seatsurge.user.UserRepository;

/**
 * The flash-sale guarantee: however many fans race, a seat is held by at most one of them, and every
 * hold is all-or-nothing. Runs with the Redis fast path on; {@link HoldConcurrencyDbOnlyTest} re-runs the
 * same scenarios with Redis disabled to prove Postgres optimistic locking is sufficient by itself.
 */
class HoldConcurrencyTest extends IntegrationTest {

    private static final int FANS = 200;
    private static final int SEATS = 10;

    @Autowired
    private HoldService holdService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private TestEvent event;
    private List<AuthUser> fans;

    @BeforeEach
    void setUp() throws Exception {
        event = createPublishedEvent(tokenFor("ORGANIZER"), SEATS, 6, true);
        // Created directly (not via /register) to keep 200 BCrypt hashes out of the test's run time.
        fans = new ArrayList<>();
        for (int i = 0; i < FANS; i++) {
            User user = userRepository.save(new User("fan-" + UUID.randomUUID() + "@example.com", "n/a", "Fan", Role.FAN));
            fans.add(new AuthUser(user.getId(), user.getEmail(), Role.FAN));
        }
    }

    @Test
    void twoHundredFansTenSeatsExactlyTenWinners() throws Exception {
        // Fan i wants seat i % 10: every seat is contested by 20 fans at the same instant.
        RaceResult result = race(i -> List.of(event.seatIds().get(i % SEATS)));

        assertThat(result.unexpectedErrors).isEmpty();
        assertThat(result.winners).hasSize(SEATS);
        assertThat(result.conflicts.get()).isEqualTo(FANS - SEATS);
        assertThat(heldSeatCount()).isEqualTo(SEATS);
        assertThat(distinctHoldsOwningSeats()).isEqualTo(SEATS);
        assertEveryWinnerOwnsExactlyTheirSeats(result);
    }

    @Test
    void overlappingMultiSeatRequestsAreAllOrNothing() throws Exception {
        // Each fan wants 3 random seats; requests overlap heavily.
        Random random = new Random(42);
        List<List<Long>> wishes = new ArrayList<>();
        for (int i = 0; i < FANS; i++) {
            List<Long> seats = new ArrayList<>(event.seatIds());
            Collections.shuffle(seats, random);
            wishes.add(seats.subList(0, 3));
        }
        RaceResult result = race(wishes::get);

        assertThat(result.unexpectedErrors).isEmpty();
        assertThat(result.winners).isNotEmpty();
        // No seat in two holds, and held seats are exactly the winners' seats (no partial holds left behind).
        long seatsInWinningHolds = result.winners.values().stream().mapToLong(h -> h.seats().size()).sum();
        assertThat(result.winners.values()).allSatisfy(h -> assertThat(h.seats()).hasSize(3));
        assertThat(heldSeatCount()).isEqualTo(seatsInWinningHolds);
        assertThat(seatsInWinningHolds).isLessThanOrEqualTo(SEATS);
        assertEveryWinnerOwnsExactlyTheirSeats(result);
    }

    // ---------- harness ----------

    private RaceResult race(java.util.function.IntFunction<List<Long>> seatsForFan) throws Exception {
        RaceResult result = new RaceResult();
        CountDownLatch startGun = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < FANS; i++) {
                int fan = i;
                futures.add(pool.submit(() -> {
                    startGun.await();
                    try {
                        HoldResponse hold = holdService.createHold(event.eventId(), fans.get(fan), seatsForFan.apply(fan), null);
                        result.winners.put(fan, hold);
                    } catch (ApiException e) {
                        if ("SEATS_UNAVAILABLE".equals(e.getCode())) {
                            result.conflicts.incrementAndGet();
                        } else {
                            result.unexpectedErrors.add(e.getCode() + ": " + e.getMessage());
                        }
                    } catch (RuntimeException e) {
                        result.unexpectedErrors.add(e.toString());
                    }
                    return null;
                }));
            }
            startGun.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        }
        return result;
    }

    private void assertEveryWinnerOwnsExactlyTheirSeats(RaceResult result) {
        result.winners.values().forEach(hold -> {
            List<Long> owned = jdbc.queryForList(
                    "select id from event_seats where hold_id = ? and status = 'HELD' order by id", Long.class, hold.id());
            List<Long> expected = hold.seats().stream().map(HeldSeatView::eventSeatId).sorted().toList();
            assertThat(owned).isEqualTo(expected);
        });
    }

    private long heldSeatCount() {
        return jdbc.queryForObject("select count(*) from event_seats where event_id = ? and status = 'HELD'",
                Long.class, event.eventId());
    }

    private long distinctHoldsOwningSeats() {
        return jdbc.queryForObject("select count(distinct hold_id) from event_seats where event_id = ? and status = 'HELD'",
                Long.class, event.eventId());
    }

    private static final class RaceResult {
        final Map<Integer, HoldResponse> winners = new ConcurrentHashMap<>();
        final AtomicInteger conflicts = new AtomicInteger();
        final List<String> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());
    }
}
