package io.theapp.platform.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class OutboxLeaseStore {

    private final JdbcTemplate jdbcTemplate;
    private final Counter claimedCounter;
    private final Counter publishedCounter;
    private final Counter retriedCounter;
    private final Counter failedCounter;

    public OutboxLeaseStore(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.claimedCounter = meterRegistry.counter("theapp.outbox.claimed");
        this.publishedCounter = meterRegistry.counter("theapp.outbox.published");
        this.retriedCounter = meterRegistry.counter("theapp.outbox.retried");
        this.failedCounter = meterRegistry.counter("theapp.outbox.failed");
    }

    @Transactional
    public List<OutboxEvent> claimBatch(String owner, int batchSize, Duration leaseDuration) {
        Instant now = Instant.now();
        List<OutboxEvent> events = jdbcTemplate.query("""
                SELECT event_id, aggregate_type, aggregate_id, event_type, payload, attempts, created_at
                FROM outbox_event
                WHERE status = 'PENDING'
                  AND available_at <= ?
                  AND (lease_until IS NULL OR lease_until < ?)
                ORDER BY created_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """,
                (rs, rowNum) -> new OutboxEvent(
                        rs.getString("event_id"),
                        rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getInt("attempts") + 1,
                        rs.getTimestamp("created_at").toInstant()),
                Timestamp.from(now), Timestamp.from(now), batchSize);

        Instant leaseUntil = now.plus(leaseDuration);
        for (OutboxEvent event : events) {
            jdbcTemplate.update("""
                    UPDATE outbox_event
                    SET status = 'IN_FLIGHT', lease_owner = ?, lease_until = ?, attempts = attempts + 1, last_error = NULL
                    WHERE event_id = ?
                    """,
                    owner, Timestamp.from(leaseUntil), event.eventId());
        }
        claimedCounter.increment(events.size());
        return events;
    }

    public void markPublished(String eventId, String owner) {
        int updated = jdbcTemplate.update("""
                UPDATE outbox_event
                SET status = 'PUBLISHED', published_at = ?, lease_owner = NULL, lease_until = NULL, last_error = NULL
                WHERE event_id = ? AND status = 'IN_FLIGHT' AND lease_owner = ?
                """,
                Timestamp.from(Instant.now()), eventId, owner);
        if (updated == 1) {
            publishedCounter.increment();
        }
    }

    public void markFailed(OutboxEvent event, String owner, int maxAttempts, Duration baseBackoff, Throwable failure) {
        String message = truncate(failure == null ? "unknown delivery failure" : failure.toString(), 1000);
        if (event.attempts() >= maxAttempts) {
            int updated = jdbcTemplate.update("""
                    UPDATE outbox_event
                    SET status = 'FAILED', lease_owner = NULL, lease_until = NULL, last_error = ?
                    WHERE event_id = ? AND status = 'IN_FLIGHT' AND lease_owner = ?
                    """,
                    message, event.eventId(), owner);
            if (updated == 1) {
                failedCounter.increment();
            }
            return;
        }

        long multiplier = 1L << Math.min(event.attempts() - 1, 10);
        Instant availableAt = Instant.now().plus(baseBackoff.multipliedBy(multiplier));
        int updated = jdbcTemplate.update("""
                UPDATE outbox_event
                SET status = 'PENDING', available_at = ?, lease_owner = NULL, lease_until = NULL, last_error = ?
                WHERE event_id = ? AND status = 'IN_FLIGHT' AND lease_owner = ?
                """,
                Timestamp.from(availableAt), message, event.eventId(), owner);
        if (updated == 1) {
            retriedCounter.increment();
        }
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
