package com.seatsurge.seat;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.seatsurge.event.TierAvailability;

public interface EventSeatRepository extends JpaRepository<EventSeat, Long> {

    /** Creates the sellable seats of a price tier straight from the venue layout in one statement. */
    @Modifying
    @Query(value = """
            insert into event_seats (event_id, venue_seat_id, price_tier_id, status)
            select :eventId, vs.id, :tierId, 'AVAILABLE'
            from venue_seats vs
            where vs.section_id in (:sectionIds)
            """, nativeQuery = true)
    int insertForTier(@Param("eventId") Long eventId, @Param("tierId") Long tierId,
            @Param("sectionIds") Collection<Long> sectionIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from EventSeat es where es.event.id = :eventId")
    int deleteByEventId(@Param("eventId") Long eventId);

    long countByEventId(Long eventId);

    @Query("select es from EventSeat es where es.event.id = :eventId and es.id in :ids")
    List<EventSeat> findForEvent(@Param("eventId") Long eventId, @Param("ids") Collection<Long> ids);

    List<EventSeat> findByHoldId(Long holdId);

    /** HELD -> SOLD for a paid hold. Returns the number of seats sold; bumps version like every bulk write. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update EventSeat es
            set es.status = com.seatsurge.seat.SeatStatus.SOLD, es.version = es.version + 1
            where es.holdId = :holdId and es.status = com.seatsurge.seat.SeatStatus.HELD
            """)
    int markHeldSeatsSold(@Param("holdId") Long holdId);

    @Query("select es.id from EventSeat es where es.holdId = :holdId")
    List<Long> findIdsByHoldId(@Param("holdId") Long holdId);

    /**
     * Puts a hold's seats back on sale. Bumps {@code version} because a bulk update bypasses Hibernate's
     * optimistic-locking bookkeeping, and a concurrent reader must still see its stale version rejected.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update EventSeat es
            set es.status = com.seatsurge.seat.SeatStatus.AVAILABLE, es.holdId = null, es.version = es.version + 1
            where es.holdId = :holdId and es.status = com.seatsurge.seat.SeatStatus.HELD
            """)
    int releaseHeldSeats(@Param("holdId") Long holdId);

    @Query("""
            select new com.seatsurge.hold.HeldSeatView(es.id, s.name, vs.rowLabel, vs.seatNumber, pt.priceCents,
                pt.currency)
            from EventSeat es
                join es.venueSeat vs
                join vs.section s
                join es.priceTier pt
            where es.holdId = :holdId
            order by s.name, vs.rowLabel, vs.seatNumber
            """)
    List<com.seatsurge.hold.HeldSeatView> findHeldSeatViews(@Param("holdId") Long holdId);

    @Query("""
            select new com.seatsurge.event.TierAvailability(
                es.priceTier.id,
                count(es),
                sum(case when es.status = com.seatsurge.seat.SeatStatus.AVAILABLE then 1L else 0L end))
            from EventSeat es
            where es.event.id = :eventId
            group by es.priceTier.id
            """)
    List<TierAvailability> countByTier(@Param("eventId") Long eventId);

    @Query("""
            select new com.seatsurge.seat.SeatMapRow(
                es.id, s.id, s.name, vs.rowLabel, vs.seatNumber, es.status, pt.id, pt.name, pt.priceCents)
            from EventSeat es
                join es.venueSeat vs
                join vs.section s
                join es.priceTier pt
            where es.event.id = :eventId
            order by s.name, vs.rowLabel, vs.seatNumber
            """)
    List<SeatMapRow> findSeatMap(@Param("eventId") Long eventId);
}
