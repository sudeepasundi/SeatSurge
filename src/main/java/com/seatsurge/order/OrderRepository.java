package com.seatsurge.order;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByHoldId(Long holdId);

    @EntityGraph(attributePaths = "event")
    Page<Order> findByUserId(Long userId, Pageable pageable);

    long countByEventIdAndStatus(Long eventId, OrderStatus status);

    @Query("select coalesce(sum(o.amountCents), 0) from Order o where o.event.id = :eventId and o.status = :status")
    long sumAmountByEventIdAndStatus(@Param("eventId") Long eventId, @Param("status") OrderStatus status);

    /** Compare-and-set on the order status; 0 means another webhook/worker already moved it. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Order o set o.status = :to, o.updatedAt = :now where o.id = :id and o.status = :from")
    int transition(@Param("id") Long id, @Param("from") OrderStatus from, @Param("to") OrderStatus to,
            @Param("now") Instant now);
}
