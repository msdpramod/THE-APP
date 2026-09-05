CREATE TABLE consumer_inbox (
    event_id VARCHAR(64) PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    consumer_name VARCHAR(96) NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_consumer_inbox_aggregate
    ON consumer_inbox (consumer_name, aggregate_id);

CREATE TABLE driver_matching_request (
    booking_id VARCHAR(64) PRIMARY KEY,
    rider_id VARCHAR(128) NOT NULL,
    pickup_label VARCHAR(255) NOT NULL,
    pickup_latitude DOUBLE PRECISION NOT NULL,
    pickup_longitude DOUBLE PRECISION NOT NULL,
    dropoff_label VARCHAR(255) NOT NULL,
    dropoff_latitude DOUBLE PRECISION NOT NULL,
    dropoff_longitude DOUBLE PRECISION NOT NULL,
    status VARCHAR(32) NOT NULL,
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_driver_matching_status_created
    ON driver_matching_request (status, created_at);
