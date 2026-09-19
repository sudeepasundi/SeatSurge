package com.seatsurge.event;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import com.seatsurge.venue.Venue;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;

final class EventSpecifications {

    private EventSpecifications() {
    }

    /** Public search: published events only, upcoming by default, all filters optional. */
    static Specification<Event> publicSearch(String query, String city, String category, Instant from,
            Instant to, Instant now) {
        return (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("status"), EventStatus.PUBLISHED));
            predicates.add(cb.greaterThanOrEqualTo(root.get("startsAt"), from != null ? from : now));
            if (to != null) {
                predicates.add(cb.lessThan(root.get("startsAt"), to));
            }
            if (hasText(query)) {
                String like = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("title")), like),
                        cb.like(cb.lower(root.get("artist")), like)));
            }
            if (hasText(category)) {
                predicates.add(cb.equal(cb.lower(root.get("category")), category.trim().toLowerCase(Locale.ROOT)));
            }
            if (hasText(city)) {
                Join<Event, Venue> venue = root.join("venue");
                predicates.add(cb.equal(cb.lower(venue.get("city")), city.trim().toLowerCase(Locale.ROOT)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
