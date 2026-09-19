package com.seatsurge.ticket;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    long countByOrderId(Long orderId);

    Optional<Ticket> findByCode(UUID code);

    @Query("""
            select new com.seatsurge.ticket.TicketView(t.id, t.code, t.status, t.event.id, s.name, vs.rowLabel,
                vs.seatNumber, t.checkedInAt)
            from Ticket t
                join t.eventSeat es
                join es.venueSeat vs
                join vs.section s
            where t.order.id = :orderId
            order by s.name, vs.rowLabel, vs.seatNumber
            """)
    List<TicketView> findViewsByOrderId(@Param("orderId") Long orderId);

    String MY_TICKET_SELECT = """
            select new com.seatsurge.ticket.MyTicket(t.id, t.code, t.status, e.id, e.title, e.startsAt,
                v.name, v.city, s.name, vs.rowLabel, vs.seatNumber, t.checkedInAt, t.owner.id)
            from Ticket t
                join t.event e
                join e.venue v
                join t.eventSeat es
                join es.venueSeat vs
                join vs.section s
            """;

    @Query(MY_TICKET_SELECT + " where t.owner.id = :ownerId order by e.startsAt, s.name, vs.rowLabel, vs.seatNumber")
    List<MyTicket> findMyTickets(@Param("ownerId") Long ownerId);

    @Query(MY_TICKET_SELECT + " where t.id = :id")
    Optional<MyTicket> findMyTicket(@Param("id") Long id);

    /**
     * The whole check-in in one statement: the row is updated only if the ticket is valid, belongs to this
     * event and has not been scanned yet. Two gates scanning the same code at the same instant cannot
     * both succeed; Postgres serializes the updates on the row and the loser matches 0 rows.
     */
    @Modifying
    @Query(value = """
            update tickets set checked_in_at = :now, checked_in_by = :staffId
            where code = :code and event_id = :eventId and status = 'VALID' and checked_in_at is null
            """, nativeQuery = true)
    int checkIn(@Param("code") UUID code, @Param("eventId") Long eventId, @Param("staffId") Long staffId,
            @Param("now") Instant now);

    /** Transfer = new owner + new code, so QR screenshots of the old ticket stop working. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update tickets set owner_id = :toUserId, code = :newCode
            where id = :id and owner_id = :fromUserId and status = 'VALID' and checked_in_at is null
            """, nativeQuery = true)
    int transfer(@Param("id") Long id, @Param("fromUserId") Long fromUserId, @Param("toUserId") Long toUserId,
            @Param("newCode") UUID newCode);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Ticket t set t.status = com.seatsurge.ticket.TicketStatus.CANCELLED
            where t.event.id = :eventId and t.status = com.seatsurge.ticket.TicketStatus.VALID
            """)
    int cancelAllForEvent(@Param("eventId") Long eventId);

    @Query("select count(t) from Ticket t where t.event.id = :eventId and t.checkedInAt is not null")
    long countCheckedIn(@Param("eventId") Long eventId);
}
