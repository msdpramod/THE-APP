CREATE TABLE ride_booking (
    booking_id VARCHAR(36) PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    request_fingerprint VARCHAR(64) NOT NULL,
    rider_id VARCHAR(128) NOT NULL,
    pickup_label VARCHAR(255) NOT NULL,
    pickup_latitude DOUBLE PRECISION NOT NULL,
    pickup_longitude DOUBLE PRECISION NOT NULL,
    dropoff_label VARCHAR(255) NOT NULL,
    dropoff_latitude DOUBLE PRECISION NOT NULL,
    dropoff_longitude DOUBLE PRECISION NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_ride_booking_rider_created
    ON ride_booking (rider_id, created_at);
