package io.theapp.platform.ride;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/v1/rides/bookings")
public class RideBookingController {

    private final Map<String, RideBookingResponse> bookingsById = new ConcurrentHashMap<>();
    private final Map<String, String> bookingIdByIdempotencyKey = new ConcurrentHashMap<>();

    @PostMapping
    public ResponseEntity<RideBookingResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RideBookingRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        String existingId = bookingIdByIdempotencyKey.get(idempotencyKey);
        if (existingId != null) {
            return ResponseEntity.ok(bookingsById.get(existingId));
        }

        String bookingId = UUID.randomUUID().toString();
        RideBookingResponse booking = new RideBookingResponse(
                bookingId,
                request.riderId(),
                request.pickup(),
                request.dropoff(),
                RideStatus.REQUESTED,
                Instant.now());

        String winningId = bookingIdByIdempotencyKey.putIfAbsent(idempotencyKey, bookingId);
        if (winningId != null) {
            return ResponseEntity.ok(bookingsById.get(winningId));
        }

        bookingsById.put(bookingId, booking);
        return ResponseEntity.status(HttpStatus.CREATED).body(booking);
    }

    @GetMapping("/{bookingId}")
    public ResponseEntity<RideBookingResponse> get(@PathVariable String bookingId) {
        return Optional.ofNullable(bookingsById.get(bookingId))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public enum RideStatus {
        REQUESTED,
        MATCHING,
        DRIVER_ASSIGNED,
        DRIVER_ARRIVING,
        IN_PROGRESS,
        COMPLETED,
        CANCELLED
    }

    public record RideBookingRequest(
            @NotBlank String riderId,
            @NotNull @Valid GeoPoint pickup,
            @NotNull @Valid GeoPoint dropoff) {}

    public record GeoPoint(
            @NotBlank String label,
            @DecimalMin("-90.0") @DecimalMax("90.0") double latitude,
            @DecimalMin("-180.0") @DecimalMax("180.0") double longitude) {}

    public record RideBookingResponse(
            String bookingId,
            String riderId,
            GeoPoint pickup,
            GeoPoint dropoff,
            RideStatus status,
            Instant createdAt) {}
}
