package io.theapp.platform.matching;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:matching-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "the-app.outbox.publisher.enabled=false",
        "the-app.matching.consumer.enabled=false"
})
class DriverMatchingInboxStoreTest {

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
    void duplicateEventIdIsNoOpAndCreatesOneMatchingRequest() {
        String eventId = "evt-ride-1";
        DriverMatchingInboxStore.RideRequestedMessage message = message("ride-1");

        assertThat(inboxStore.accept(eventId, message))
                .isEqualTo(DriverMatchingInboxStore.ProcessResult.ACCEPTED);
        assertThat(inboxStore.accept(eventId, message))
                .isEqualTo(DriverMatchingInboxStore.ProcessResult.DUPLICATE);

        Integer inboxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consumer_inbox WHERE event_id = ?", Integer.class, eventId);
        Integer matchingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM driver_matching_request WHERE booking_id = ?", Integer.class, message.bookingId());

        assertThat(inboxCount).isEqualTo(1);
        assertThat(matchingCount).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateDeliveryCreatesOneInboxRowAndOneMatchingRequest() throws Exception {
        String eventId = "evt-ride-concurrent";
        DriverMatchingInboxStore.RideRequestedMessage message = message("ride-concurrent");
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
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS));
            assertThat(results)
                    .containsExactlyInAnyOrder(
                            DriverMatchingInboxStore.ProcessResult.ACCEPTED,
                            DriverMatchingInboxStore.ProcessResult.DUPLICATE);
        } finally {
            executor.shutdownNow();
        }

        Integer inboxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consumer_inbox WHERE event_id = ?", Integer.class, eventId);
        Integer matchingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM driver_matching_request WHERE booking_id = ?", Integer.class, message.bookingId());

        assertThat(inboxCount).isEqualTo(1);
        assertThat(matchingCount).isEqualTo(1);
    }

    @Test
    void acceptedEventInitializesPendingMatchingState() {
        DriverMatchingInboxStore.RideRequestedMessage message = message("ride-2");

        inboxStore.accept("evt-ride-2", message);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM driver_matching_request WHERE booking_id = ?", String.class, message.bookingId());
        assertThat(status).isEqualTo("PENDING");
    }

    @Test
    void distinctEventForExistingBookingIsNotMisclassifiedAsReplay() {
        DriverMatchingInboxStore.RideRequestedMessage message = message("ride-3");
        inboxStore.accept("evt-ride-3a", message);

        assertThatThrownBy(() -> inboxStore.accept("evt-ride-3b", message))
                .isInstanceOf(DataIntegrityViolationException.class);

        Integer secondInboxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consumer_inbox WHERE event_id = ?", Integer.class, "evt-ride-3b");
        Integer matchingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM driver_matching_request WHERE booking_id = ?", Integer.class, message.bookingId());

        assertThat(secondInboxCount).isZero();
        assertThat(matchingCount).isEqualTo(1);
    }

    private DriverMatchingInboxStore.RideRequestedMessage message(String bookingId) {
        return new DriverMatchingInboxStore.RideRequestedMessage(
                bookingId,
                "rider-42",
                new DriverMatchingInboxStore.GeoPoint("Pickup", 17.385, 78.4867),
                new DriverMatchingInboxStore.GeoPoint("Dropoff", 17.4483, 78.3915),
                Instant.parse("2026-08-29T12:00:00Z"));
    }
}
