package com.seatsurge.hold;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HoldRepository extends JpaRepository<Hold, Long> {

    boolean existsByUserIdAndEventIdAndStatus(Long userId, Long eventId, HoldStatus status);

    List<Hold> findByUserIdAndStatusOrderByExpiresAt(Long userId, HoldStatus status);

    /** Seats this fan already bought for the event (counts toward the per-user ticket limit). */
    @Query("""
            select count(es) from EventSeat es, Hold h
            where es.holdId = h.id and h.user.id = :userId and h.event.id = :eventId
              and h.status = com.seatsurge.hold.HoldStatus.CONVERTED
            """)
    long countPurchasedSeats(@Param("userId") Long userId, @Param("eventId") Long eventId);

    @Query("""
            select h.id from Hold h
            where h.status = com.seatsurge.hold.HoldStatus.ACTIVE and h.expiresAt <= :now
            order by h.expiresAt
            """)
    List<Long> findExpiredActiveIds(@Param("now") Instant now, Pageable pageable);

    /**
     * Compare-and-set on the status. Returns 0 if the hold was not ACTIVE any more, which is what makes
     * release, expiry and (later) payment conversion safe to race against each other.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Hold h set h.status = :to, h.updatedAt = :now
            where h.id = :id and h.status = com.seatsurge.hold.HoldStatus.ACTIVE
            """)
    int transitionFromActive(@Param("id") Long id, @Param("to") HoldStatus to, @Param("now") Instant now);

    /** Same as {@link #transitionFromActive} but only if the hold really is past its expiry. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Hold h set h.status = com.seatsurge.hold.HoldStatus.EXPIRED, h.updatedAt = :now
            where h.id = :id and h.status = com.seatsurge.hold.HoldStatus.ACTIVE and h.expiresAt <= :now
            """)
    int expireIfDue(@Param("id") Long id, @Param("now") Instant now);
}
