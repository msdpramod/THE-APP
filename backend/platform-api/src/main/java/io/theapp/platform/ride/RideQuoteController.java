package io.theapp.platform.ride;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/rides")
public class RideQuoteController {

    @PostMapping("/quote")
    public ResponseEntity<RideQuoteResponse> quote(@Valid @RequestBody RideQuoteRequest request) {
        double distanceKm = haversineKm(
                request.pickup().latitude(), request.pickup().longitude(),
                request.dropoff().latitude(), request.dropoff().longitude());

        BigDecimal baseFare = BigDecimal.valueOf(45);
        BigDecimal distanceFare = BigDecimal.valueOf(distanceKm)
                .multiply(BigDecimal.valueOf(14));
        BigDecimal estimatedFare = baseFare.add(distanceFare)
                .max(BigDecimal.valueOf(60))
                .setScale(2, RoundingMode.HALF_UP);

        long etaMinutes = Math.max(3, Math.round(distanceKm * 2.2));
        return ResponseEntity.ok(new RideQuoteResponse(
                estimatedFare,
                "INR",
                Math.round(distanceKm * 100.0) / 100.0,
                etaMinutes,
                Instant.now()));
    }

    private double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double earthRadiusKm = 6371.0088;
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        return earthRadiusKm * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public record RideQuoteRequest(@NotNull GeoPoint pickup, @NotNull GeoPoint dropoff) {}

    public record GeoPoint(
            @NotBlank String label,
            @DecimalMin("-90.0") @DecimalMax("90.0") double latitude,
            @DecimalMin("-180.0") @DecimalMax("180.0") double longitude) {}

    public record RideQuoteResponse(
            BigDecimal estimatedFare,
            String currency,
            double distanceKm,
            long estimatedArrivalMinutes,
            Instant quotedAt) {}
}
