package com.seatsurge.venue;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SectionRepository extends JpaRepository<Section, Long> {

    List<Section> findByVenueIdOrderByName(Long venueId);

    Optional<Section> findByIdAndVenueId(Long id, Long venueId);

    boolean existsByVenueIdAndNameIgnoreCase(Long venueId, String name);
}
