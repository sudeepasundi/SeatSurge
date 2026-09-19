package com.seatsurge.venue;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VenueSeatRepository extends JpaRepository<VenueSeat, Long> {

    /** Generates seats 1..count of a row in a single statement instead of one INSERT per seat. */
    @Modifying
    @Query(value = """
            insert into venue_seats (section_id, row_label, seat_number)
            select :sectionId, :rowLabel, gs from generate_series(1, :count) gs
            """, nativeQuery = true)
    int insertRow(@Param("sectionId") Long sectionId, @Param("rowLabel") String rowLabel,
            @Param("count") int count);

    @Query("""
            select new com.seatsurge.venue.SectionRowCount(vs.section.id, vs.rowLabel, count(vs))
            from VenueSeat vs
            where vs.section.venue.id = :venueId
            group by vs.section.id, vs.rowLabel
            order by vs.rowLabel
            """)
    List<SectionRowCount> countRowsByVenue(@Param("venueId") Long venueId);
}
