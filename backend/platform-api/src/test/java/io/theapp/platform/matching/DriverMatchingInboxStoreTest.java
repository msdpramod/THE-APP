package io.theapp.platform.matching;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

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
    void acceptedEventInitializesPendingMatchingState() {
        DriverMatchingInboxStore.RideRequestedMessage message = message("ride-2");

        inboxStore.accept("evt-ride-2", message);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM driver_matching_request WHERE booking_id = ?", String.class, message.bookingId());
        assertThat(status).isEqualTo("PENDING");
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
