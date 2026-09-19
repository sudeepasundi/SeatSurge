package com.seatsurge.ticket;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    long countByOrderId(Long orderId);

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
}
