package io.theapp.platform.outbox;

import java.time.Instant;

public record OutboxEvent(
        String eventId,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payload,
        int attempts,
        Instant createdAt) {
}
