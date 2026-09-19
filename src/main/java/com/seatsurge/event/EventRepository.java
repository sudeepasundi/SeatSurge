package com.seatsurge.event;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface EventRepository extends JpaRepository<Event, Long>, JpaSpecificationExecutor<Event> {

    @Override
    @EntityGraph(attributePaths = "venue")
    Page<Event> findAll(Specification<Event> spec, Pageable pageable);

    @EntityGraph(attributePaths = "venue")
    Page<Event> findByOrganizerId(Long organizerId, Pageable pageable);

    boolean existsByVenueId(Long venueId);
}
