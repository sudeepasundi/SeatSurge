package com.seatsurge.event;

import java.time.Instant;

import com.seatsurge.common.entity.BaseEntity;
import com.seatsurge.user.User;
import com.seatsurge.venue.Venue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "events")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Event extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_id")
    private Venue venue;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organizer_id")
    private User organizer;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String artist;

    private String description;

    private String category;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "sale_starts_at", nullable = false)
    private Instant saleStartsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventStatus status = EventStatus.DRAFT;

    @Column(name = "max_tickets_per_user", nullable = false)
    private int maxTicketsPerUser;

    /** High-demand drop: fans must pass the virtual waiting room before they can hold seats. */
    @Column(name = "waiting_room_enabled", nullable = false)
    private boolean waitingRoomEnabled;

    /** How many queued fans are let in per minute once the sale opens. */
    @Column(name = "admission_rate_per_minute", nullable = false)
    private int admissionRatePerMinute = 600;

    public Event(User organizer) {
        this.organizer = organizer;
    }

    public Long organizerId() {
        return organizer.getId();
    }

    public SalePhase salePhase(Instant now) {
        if (status == EventStatus.CANCELLED || !now.isBefore(startsAt)) {
            return SalePhase.CLOSED;
        }
        if (status == EventStatus.DRAFT || now.isBefore(saleStartsAt)) {
            return SalePhase.UPCOMING;
        }
        return SalePhase.ON_SALE;
    }
}
