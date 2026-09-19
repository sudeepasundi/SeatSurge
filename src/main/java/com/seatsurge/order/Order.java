package com.seatsurge.order;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.seatsurge.common.entity.BaseEntity;
import com.seatsurge.event.Event;
import com.seatsurge.hold.Hold;
import com.seatsurge.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id")
    private Event event;

    /** One order per hold (unique), which also makes checkout naturally idempotent per hold. */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hold_id", unique = true)
    private Hold hold;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status = OrderStatus.PENDING;

    public Order(User user, Event event, Hold hold, long amountCents, String currency) {
        this.user = user;
        this.event = event;
        this.hold = hold;
        this.amountCents = amountCents;
        this.currency = currency;
    }

    public Long userId() {
        return user.getId();
    }

    public Long holdId() {
        return hold.getId();
    }
}
