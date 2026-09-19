package com.seatsurge.common.outbox;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Claims due events. SKIP LOCKED lets several app instances poll concurrently without blocking each
     * other or picking the same row. PROCESSING rows whose lease ran out are due again (crashed worker).
     */
    @Query(value = """
            select * from outbox_events
            where status in ('PENDING', 'PROCESSING') and next_attempt_at <= :now
            order by id
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<OutboxEvent> lockDue(@Param("now") Instant now, @Param("limit") int limit);

    List<OutboxEvent> findByAggregateTypeAndAggregateIdOrderById(String aggregateType, String aggregateId);
}
