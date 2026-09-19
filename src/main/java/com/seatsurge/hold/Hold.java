package com.seatsurge.hold;

import java.time.Instant;

import com.seatsurge.common.entity.BaseEntity;
import com.seatsurge.event.Event;
import com.seatsurge.user.User;

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

/** A time-limited reservation of one or more seats while the fan checks out. */
@Entity
@Table(name = "holds")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Hold extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id")
    private Event event;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HoldStatus status = HoldStatus.ACTIVE;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "lock_token")
    private String lockToken;

    public Hold(User user, Event event, Instant expiresAt, String lockToken) {
        this.user = user;
        this.event = event;
        this.expiresAt = expiresAt;
        this.lockToken = lockToken;
    }

    /** Checkout extends the hold so it outlives the payment session (never shortens it). */
    public void extendTo(Instant newExpiry) {
        if (newExpiry.isAfter(expiresAt)) {
            expiresAt = newExpiry;
        }
    }

    public Long userId() {
        return user.getId();
    }

    public Long eventId() {
        return event.getId();
    }
}
