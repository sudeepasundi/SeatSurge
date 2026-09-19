package com.seatsurge.common.outbox;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.seatsurge.common.config.SeatSurgeProperties;

import tools.jackson.databind.ObjectMapper;

/**
 * Polls the outbox and runs handlers. Three short steps per batch:
 * claim (lease rows with SKIP LOCKED) -> handle each event outside that transaction -> record the outcome.
 * Handlers therefore never run while holding row locks, and one failing event never rolls back another.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outboxRepository;
    private final Map<String, OutboxHandler> handlers;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final SeatSurgeProperties.Outbox config;

    public OutboxPublisher(OutboxRepository outboxRepository, List<OutboxHandler> handlers, ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager, Clock clock, SeatSurgeProperties properties) {
        this.outboxRepository = outboxRepository;
        this.handlers = handlers.stream().collect(Collectors.toMap(OutboxHandler::eventType, Function.identity()));
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.config = properties.outbox();
    }

    @Scheduled(fixedDelayString = "${seatsurge.outbox.poll-interval}", initialDelayString = "${seatsurge.outbox.poll-interval}")
    public void poll() {
        processBatch();
    }

    /** @return number of events claimed in this batch */
    public int processBatch() {
        List<OutboxEvent> claimed = tx.execute(status -> {
            List<OutboxEvent> due = outboxRepository.lockDue(clock.instant(), config.batchSize());
            due.forEach(e -> e.lease(clock.instant(), config.lease()));
            return due;
        });
        for (OutboxEvent event : claimed) {
            String error = run(event);
            tx.executeWithoutResult(status -> outboxRepository.findById(event.getId()).ifPresent(e -> {
                if (error == null) {
                    e.markDone(clock.instant());
                } else {
                    e.markFailed(error, clock.instant(), config.maxAttempts());
                }
            }));
        }
        return claimed.size();
    }

    private String run(OutboxEvent event) {
        OutboxHandler handler = handlers.get(event.getEventType());
        if (handler == null) {
            return "No handler for event type " + event.getEventType();
        }
        try {
            handler.handle(objectMapper.readTree(event.getPayload()));
            return null;
        } catch (Exception e) {
            log.warn("Outbox event {} ({}) failed on attempt {}: {}", event.getId(), event.getEventType(),
                    event.getAttempts(), e.toString());
            return e.toString();
        }
    }
}
