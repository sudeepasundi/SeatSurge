package com.seatsurge.event;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PriceTierRepository extends JpaRepository<PriceTier, Long> {

    List<PriceTier> findByEventIdOrderByPriceCentsDesc(Long eventId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from PriceTier pt where pt.event.id = :eventId")
    int deleteByEventId(@Param("eventId") Long eventId);

    @Query("""
            select new com.seatsurge.event.EventPrice(pt.event.id, min(pt.priceCents), min(pt.currency))
            from PriceTier pt
            where pt.event.id in :eventIds
            group by pt.event.id
            """)
    List<EventPrice> findMinPrices(@Param("eventIds") Collection<Long> eventIds);
}
