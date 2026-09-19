package com.seatsurge.event;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "price_tiers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PriceTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id")
    private Event event;

    @Column(nullable = false)
    private String name;

    /** Money is kept in minor units (cents) to avoid floating-point rounding. */
    @Column(name = "price_cents", nullable = false)
    private long priceCents;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    public PriceTier(Event event, String name, long priceCents, String currency) {
        this.event = event;
        this.name = name;
        this.priceCents = priceCents;
        this.currency = currency;
    }
}
