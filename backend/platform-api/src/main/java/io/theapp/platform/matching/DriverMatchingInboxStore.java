package io.theapp.platform.matching;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class DriverMatchingInboxStore {

    private final DriverMatchingInboxTransaction transaction;

    public DriverMatchingInboxStore(DriverMatchingInboxTransaction transaction) {
        this.transaction = transaction;
    }

    public ProcessResult accept(String eventId, RideRequestedMessage message) {
        if (transaction.eventExists(eventId)) {
            return ProcessResult.DUPLICATE;
        }

        try {
            transaction.acceptNew(eventId, message);
            return ProcessResult.ACCEPTED;
        } catch (DuplicateKeyException duplicateKeyException) {
            // A concurrent delivery of the same event may win after the initial lookup.
            // Only classify the failure as a replay when that exact event ID now exists.
            // Other uniqueness violations (for example, a distinct event for an already
            // initialized booking) remain failures instead of being silently swallowed.
            if (transaction.eventExists(eventId)) {
                return ProcessResult.DUPLICATE;
            }
            throw duplicateKeyException;
        }
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
