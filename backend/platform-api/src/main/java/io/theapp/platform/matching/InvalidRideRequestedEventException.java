package io.theapp.platform.matching;

/**
 * Signals that a RideRequested broker record is permanently invalid and should
 * not consume the retry budget reserved for transient processing failures.
 */
public class InvalidRideRequestedEventException extends RuntimeException {

    public InvalidRideRequestedEventException(String message) {
        super(message);
    }

    public InvalidRideRequestedEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
