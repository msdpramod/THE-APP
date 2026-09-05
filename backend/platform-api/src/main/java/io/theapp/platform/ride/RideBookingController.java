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

@RestController
@RequestMapping("/api/v1/rides/bookings")
public class RideBookingController {

    private final RideBookingStore store;

    public RideBookingController(RideBookingStore store) {
        this.store = store;
    }

    @PostMapping
    public ResponseEntity<RideBookingResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RideBookingRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            return ResponseEntity.badRequest().build();
        }

        RideBookingStore.CreateResult result = store.create(idempotencyKey, request);
        return switch (result.outcome()) {
            case CREATED -> ResponseEntity.status(HttpStatus.CREATED).body(result.booking());
            case REPLAYED -> ResponseEntity.ok(result.booking());
            case CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT).build();
        };
    }

    @GetMapping("/{bookingId}")
    public ResponseEntity<RideBookingResponse> get(@PathVariable String bookingId) {
        return store.findById(bookingId)
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
