ALTER TABLE outbox_event ADD COLUMN lease_owner VARCHAR(128) NULL;
ALTER TABLE outbox_event ADD COLUMN lease_until TIMESTAMP NULL;
ALTER TABLE outbox_event ADD COLUMN last_error VARCHAR(1000) NULL;

CREATE INDEX idx_outbox_event_lease
    ON outbox_event (status, available_at, lease_until, created_at);
