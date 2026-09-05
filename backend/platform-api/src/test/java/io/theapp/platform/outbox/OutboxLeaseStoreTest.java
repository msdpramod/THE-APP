package io.theapp.platform.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:outbox-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "the-app.outbox.publisher.enabled=false"
})
class OutboxLeaseStoreTest {

    @Autowired
    private OutboxLeaseStore leaseStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM ride_booking");
    }

    @Test
    void claimsBoundedBatchAndMarksPublishedOnlyForLeaseOwner() {
        String first = insertPendingEvent();
        insertPendingEvent();
        insertPendingEvent();

        List<OutboxEvent> claimed = leaseStore.claimBatch("worker-a", 2, Duration.ofSeconds(30));

        assertThat(claimed).hasSize(2);
        leaseStore.markPublished(first, "wrong-worker");
        assertThat(status(first)).isEqualTo("IN_FLIGHT");

        leaseStore.markPublished(first, "worker-a");
        assertThat(status(first)).isEqualTo("PUBLISHED");
    }

    @Test
    void failedDeliveryReturnsToPendingWithBackoffThenBecomesTerminal() {
        String eventId = insertPendingEvent();
        OutboxEvent firstAttempt = leaseStore.claimBatch("worker-a", 1, Duration.ofSeconds(30)).get(0);

        leaseStore.markFailed(firstAttempt, "worker-a", 2, Duration.ofMillis(1), new IllegalStateException("broker unavailable"));

        assertThat(status(eventId)).isEqualTo("PENDING");
        Integer attempts = jdbcTemplate.queryForObject("SELECT attempts FROM outbox_event WHERE event_id = ?", Integer.class, eventId);
        assertThat(attempts).isEqualTo(1);

        jdbcTemplate.update("UPDATE outbox_event SET available_at = ? WHERE event_id = ?", Timestamp.from(Instant.EPOCH), eventId);
        OutboxEvent secondAttempt = leaseStore.claimBatch("worker-b", 1, Duration.ofSeconds(30)).get(0);
        leaseStore.markFailed(secondAttempt, "worker-b", 2, Duration.ofMillis(1), new IllegalStateException("still unavailable"));

        assertThat(status(eventId)).isEqualTo("FAILED");
        String lastError = jdbcTemplate.queryForObject("SELECT last_error FROM outbox_event WHERE event_id = ?", String.class, eventId);
        assertThat(lastError).contains("still unavailable");
    }

    private String insertPendingEvent() {
        String eventId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO outbox_event (
                    event_id, aggregate_type, aggregate_id, event_type, payload,
                    status, attempts, available_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                eventId, "RIDE_BOOKING", UUID.randomUUID().toString(), "RideRequested", "{}",
                "PENDING", 0, Timestamp.from(now.minusSeconds(1)), Timestamp.from(now));
        return eventId;
    }

    private String status(String eventId) {
        return jdbcTemplate.queryForObject("SELECT status FROM outbox_event WHERE event_id = ?", String.class, eventId);
    }
}
