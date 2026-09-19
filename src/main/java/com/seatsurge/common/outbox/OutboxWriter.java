package com.seatsurge.common.outbox;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class OutboxWriter {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** Must run inside the caller's transaction, so the event commits (or rolls back) with the state change. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String aggregateType, Object aggregateId, String eventType, Object payload) {
        outboxRepository.save(new OutboxEvent(aggregateType, String.valueOf(aggregateId), eventType,
                objectMapper.writeValueAsString(payload), clock.instant()));
    }
}
