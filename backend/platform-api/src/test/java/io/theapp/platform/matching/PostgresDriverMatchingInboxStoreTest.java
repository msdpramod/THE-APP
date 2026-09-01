package io.theapp.platform.matching;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "THE_APP_POSTGRES_IT", matches = "true")
@SpringBootTest(properties = {
        "the-app.outbox.publisher.enabled=false",
        "the-app.matching.consumer.enabled=false"
})
class PostgresDriverMatchingInboxStoreTest {

    @Autowired
    private DriverMatchingInboxStore inboxStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM consumer_inbox");
        jdbcTemplate.update("DELETE FROM driver_matching_request");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM ride_booking");
    }

    @Test
    void duplicateEventIdIsReplaySafeOnPostgres() {
        String eventId = "evt-postgres-replay";
        DriverMatchingInboxStore.RideRequestedMessage message = message("ride-postgres-replay");

        assertThat(inboxStore.accept(eventId, message))
                .isEqualTo(DriverMatchingInboxStore.ProcessResult.ACCEPTED);
        assertThat(inboxStore.accept(eventId, message))
                .isEqualTo(DriverMatchingInboxStore.ProcessResult.DUPLICATE);

        assertThat(count("consumer_inbox", "event_id", eventId)).isEqualTo(1);
        assertThat(count("driver_matching_request", "booking_id", message.bookingId())).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateDeliveryIsReplaySafeOnPostgres() throws Exception {
        String eventId = "evt-postgres-concurrent";
        DriverMatchingInboxStore.RideRequestedMessage message = message("ride-postgres-concurrent");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Callable<DriverMatchingInboxStore.ProcessResult> delivery = () -> {
            ready.countDown();
            start.await();
            return inboxStore.accept(eventId, message);
        };

        try {
            Future<DriverMatchingInboxStore.ProcessResult> first = executor.submit(delivery);
            Future<DriverMatchingInboxStore.ProcessResult> second = executor.submit(delivery);

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<DriverMatchingInboxStore.ProcessResult> results = List.of(
                    first.get(15, TimeUnit.SECONDS),
                    second.get(15, TimeUnit.SECONDS));

            assertThat(results).containsExactlyInAnyOrder(
                    DriverMatchingInboxStore.ProcessResult.ACCEPTED,
                    DriverMatchingInboxStore.ProcessResult.DUPLICATE);
        } finally {
            executor.shutdownNow();
        }

        assertThat(count("consumer_inbox", "event_id", eventId)).isEqualTo(1);
        assertThat(count("driver_matching_request", "booking_id", message.bookingId())).isEqualTo(1);
    }

    @Test
    void distinctEventForExistingBookingRollsBackInboxOnPostgres() {
        DriverMatchingInboxStore.RideRequestedMessage message = message("ride-postgres-integrity");
        inboxStore.accept("evt-postgres-integrity-a", message);

        assertThatThrownBy(() -> inboxStore.accept("evt-postgres-integrity-b", message))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(count("consumer_inbox", "event_id", "evt-postgres-integrity-b")).isZero();
        assertThat(count("driver_matching_request", "booking_id", message.bookingId())).isEqualTo(1);
    }

    private int count(String table, String column, String value) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
        return count == null ? 0 : count;
    }

    private DriverMatchingInboxStore.RideRequestedMessage message(String bookingId) {
        return new DriverMatchingInboxStore.RideRequestedMessage(
                bookingId,
                "rider-postgres",
                new DriverMatchingInboxStore.GeoPoint("Pickup", 17.385, 78.4867),
                new DriverMatchingInboxStore.GeoPoint("Dropoff", 17.4483, 78.3915),
                Instant.parse("2026-09-01T12:00:00Z"));
    }
}
