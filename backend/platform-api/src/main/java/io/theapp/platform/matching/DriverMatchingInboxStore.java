package io.theapp.platform.matching;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

@Service
public class DriverMatchingInboxStore {

    private static final String CONSUMER_NAME = "driver-matching-v1";

    private final JdbcTemplate jdbcTemplate;

    public DriverMatchingInboxStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public ProcessResult accept(String eventId, RideRequestedMessage message) {
        Instant now = Instant.now();
        int inserted = jdbcTemplate.update("""
                INSERT INTO consumer_inbox (
                    event_id, event_type, aggregate_id, consumer_name, received_at, processed_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """,
                eventId,
                "RideRequested",
                message.bookingId(),
                CONSUMER_NAME,
                Timestamp.from(now),
                Timestamp.from(now));

        if (inserted == 0) {
            return ProcessResult.DUPLICATE;
        }

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

        return ProcessResult.ACCEPTED;
    }

    public enum ProcessResult {
        ACCEPTED,
        DUPLICATE
    }

    public record RideRequestedMessage(
            String bookingId,
            String riderId,
            GeoPoint pickup,
            GeoPoint dropoff,
            Instant requestedAt) {
    }

    public record GeoPoint(String label, double latitude, double longitude) {
    }
}
