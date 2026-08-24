package io.theapp.platform.ride;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class RideBookingStore {

    private final JdbcTemplate jdbcTemplate;

    public RideBookingStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public CreateResult create(String idempotencyKey, RideBookingController.RideBookingRequest request) {
        String fingerprint = fingerprint(request);
        Optional<StoredBooking> existing = findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }

        RideBookingController.RideBookingResponse booking = new RideBookingController.RideBookingResponse(
                UUID.randomUUID().toString(),
                request.riderId(),
                request.pickup(),
                request.dropoff(),
                RideBookingController.RideStatus.REQUESTED,
                Instant.now());

        try {
            jdbcTemplate.update("""
                    INSERT INTO ride_booking (
                        booking_id, idempotency_key, request_fingerprint, rider_id,
                        pickup_label, pickup_latitude, pickup_longitude,
                        dropoff_label, dropoff_latitude, dropoff_longitude,
                        status, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    booking.bookingId(), idempotencyKey, fingerprint, booking.riderId(),
                    booking.pickup().label(), booking.pickup().latitude(), booking.pickup().longitude(),
                    booking.dropoff().label(), booking.dropoff().latitude(), booking.dropoff().longitude(),
                    booking.status().name(), Timestamp.from(booking.createdAt()));
            return CreateResult.created(booking);
        } catch (DuplicateKeyException race) {
            StoredBooking winner = findByIdempotencyKey(idempotencyKey).orElseThrow(() -> race);
            return replayOrConflict(winner, fingerprint);
        }
    }

    public Optional<RideBookingController.RideBookingResponse> findById(String bookingId) {
        return jdbcTemplate.query("SELECT * FROM ride_booking WHERE booking_id = ?", this::mapStoredBooking, bookingId)
                .stream()
                .findFirst()
                .map(StoredBooking::booking);
    }

    private Optional<StoredBooking> findByIdempotencyKey(String idempotencyKey) {
        return jdbcTemplate.query("SELECT * FROM ride_booking WHERE idempotency_key = ?", this::mapStoredBooking, idempotencyKey)
                .stream()
                .findFirst();
    }

    private CreateResult replayOrConflict(StoredBooking existing, String fingerprint) {
        if (!existing.fingerprint().equals(fingerprint)) {
            return CreateResult.conflict();
        }
        return CreateResult.replayed(existing.booking());
    }

    private StoredBooking mapStoredBooking(ResultSet rs, int rowNum) throws SQLException {
        RideBookingController.GeoPoint pickup = new RideBookingController.GeoPoint(
                rs.getString("pickup_label"),
                rs.getDouble("pickup_latitude"),
                rs.getDouble("pickup_longitude"));
        RideBookingController.GeoPoint dropoff = new RideBookingController.GeoPoint(
                rs.getString("dropoff_label"),
                rs.getDouble("dropoff_latitude"),
                rs.getDouble("dropoff_longitude"));
        RideBookingController.RideBookingResponse booking = new RideBookingController.RideBookingResponse(
                rs.getString("booking_id"),
                rs.getString("rider_id"),
                pickup,
                dropoff,
                RideBookingController.RideStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant());
        return new StoredBooking(booking, rs.getString("request_fingerprint"));
    }

    private String fingerprint(RideBookingController.RideBookingRequest request) {
        String canonical = String.join("\u001f",
                request.riderId(),
                request.pickup().label(),
                Double.toString(request.pickup().latitude()),
                Double.toString(request.pickup().longitude()),
                request.dropoff().label(),
                Double.toString(request.dropoff().latitude()),
                Double.toString(request.dropoff().longitude()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }

    private record StoredBooking(RideBookingController.RideBookingResponse booking, String fingerprint) {}

    public record CreateResult(RideBookingController.RideBookingResponse booking, Outcome outcome) {
        public static CreateResult created(RideBookingController.RideBookingResponse booking) {
            return new CreateResult(booking, Outcome.CREATED);
        }

        public static CreateResult replayed(RideBookingController.RideBookingResponse booking) {
            return new CreateResult(booking, Outcome.REPLAYED);
        }

        public static CreateResult conflict() {
            return new CreateResult(null, Outcome.CONFLICT);
        }
    }

    public enum Outcome {
        CREATED,
        REPLAYED,
        CONFLICT
    }
}
