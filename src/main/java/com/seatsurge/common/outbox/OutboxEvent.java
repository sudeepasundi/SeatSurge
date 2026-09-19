package com.seatsurge.common.outbox;

import java.time.Duration;
import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A side effect (email, refund, ...) recorded in the same transaction as the state change that caused it.
 * A background publisher executes it afterwards with retries, which gives at-least-once delivery without
 * distributed transactions.
 */
@Entity
@Table(name = "outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    public static final String PENDING = "PENDING";
    public static final String PROCESSING = "PROCESSING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @Column(nullable = false)
    private String status = PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    public OutboxEvent(String aggregateType, String aggregateId, String eventType, String payload, Instant now) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.nextAttemptAt = now;
    }

    /** Reserve the event for one worker; if that worker dies, the lease runs out and another retries. */
    void lease(Instant now, Duration lease) {
        status = PROCESSING;
        attempts++;
        nextAttemptAt = now.plus(lease);
    }

    void markDone(Instant now) {
        status = DONE;
        processedAt = now;
        lastError = null;
    }

    /** Exponential backoff: 2s, 4s, 8s ... capped at one hour, then parked as FAILED. */
    void markFailed(String error, Instant now, int maxAttempts) {
        lastError = error == null ? null : error.substring(0, Math.min(error.length(), 2000));
        if (attempts >= maxAttempts) {
            status = FAILED;
            return;
        }
        status = PENDING;
        long backoffSeconds = Math.min(3600, 1L << Math.min(attempts, 12));
        nextAttemptAt = now.plusSeconds(backoffSeconds);
    }
}
