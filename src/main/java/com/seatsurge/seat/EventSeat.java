package com.seatsurge.seat;

import com.seatsurge.event.Event;
import com.seatsurge.event.PriceTier;
import com.seatsurge.venue.VenueSeat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The sellable unit: one venue seat for one event. {@code version} enables optimistic locking, which is
 * the database-level guarantee that two buyers can never both claim the same seat.
 */
@Entity
@Table(name = "event_seats")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id")
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_seat_id")
    private VenueSeat venueSeat;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "price_tier_id")
    private PriceTier priceTier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeatStatus status = SeatStatus.AVAILABLE;

    @Column(name = "hold_id")
    private Long holdId;

    @Version
    @Column(nullable = false)
    private long version;
}
