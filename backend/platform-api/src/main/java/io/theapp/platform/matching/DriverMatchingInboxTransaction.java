package io.theapp.platform.matching;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

@Service
public class DriverMatchingInboxTransaction {

    private static final String CONSUMER_NAME = "driver-matching-v1";

    private final JdbcTemplate jdbcTemplate;

    public DriverMatchingInboxTransaction(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean eventExists(String eventId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consumer_inbox WHERE event_id = ?",
                Integer.class,
                eventId);
        return count != null && count > 0;
    }

    @Transactional
    public void acceptNew(String eventId, DriverMatchingInboxStore.RideRequestedMessage message) {
        Instant now = Instant.now();

        jdbcTemplate.update("""
                INSERT INTO consumer_inbox (
                    event_id, event_type, aggregate_id, consumer_name, received_at, processed_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                eventId,
                "RideRequested",
                message.bookingId(),
                CONSUMER_NAME,
                Timestamp.from(now),
                Timestamp.from(now));

        jdbcTemplate.update("""
                INSERT INTO driver_matching_request (
                    booking_id, rider_id,
                    pickup_label, pickup_latitude, pickup_longitude,
                    dropoff_label, dropoff_latitude, dropoff_longitude,
                    status, requested_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                message.bookingId(),
                message.riderId(),
                message.pickup().label(),
                message.pickup().latitude(),
                message.pickup().longitude(),
                message.dropoff().label(),
                message.dropoff().latitude(),
                message.dropoff().longitude(),
                "PENDING",
                Timestamp.from(message.requestedAt()),
                Timestamp.from(now));
    }
}
