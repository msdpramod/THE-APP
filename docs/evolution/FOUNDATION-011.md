# Foundation 011 — Concurrent replay safety gate

## Goal

Prove that the driver-matching inbox remains replay-safe when two consumer threads receive the same `RideRequested` event at nearly the same time.

Foundation 010 moved correctness onto ordinary inserts plus database uniqueness. The sequential replay path was already covered, but the race between the pre-check and the transactional insert was still an explicit open risk.

## Change

Added a Spring Boot integration test that starts two concurrent deliveries for the same event ID and booking, releases them together, and verifies:

- exactly one delivery returns `ACCEPTED`;
- exactly one delivery returns `DUPLICATE`;
- exactly one `consumer_inbox` row exists for the event;
- exactly one `driver_matching_request` row exists for the booking;
- waits are time-bounded so CI fails instead of hanging if database locking behavior regresses.

No production code, HTTP contract, Kafka contract, schema, or feature flag changed.

## Why this is valuable

At-least-once delivery means duplicate Kafka records are normal, and consumer instances can race after rebalances, retries, or concurrent redelivery. A replay implementation that only works sequentially is not sufficient. This test validates the race path that Foundation 010 was designed to handle: the database unique key remains authoritative while the loser reclassifies only the same event ID as a duplicate.

## Risk posture

- The test currently runs against H2 in PostgreSQL compatibility mode. It proves application-level race handling in CI but does not replace PostgreSQL concurrency validation.
- Thread scheduling is nondeterministic, so the test uses a start latch to maximize overlap and bounded waits to avoid a stuck build.
- Kafka transport and the scheduled consumer remain disabled by default; this change does not enable distributed infrastructure.

## Validation

The repository CI backend job runs `mvn -B verify`, so this test is part of the normal pull-request gate. The frontend gate remains unchanged.

## Next target

Introduce real infrastructure integration tests with ephemeral PostgreSQL and Kafka-compatible brokers. Cover:

1. acknowledged outbox publication;
2. duplicate Kafka redelivery across consumer instances;
3. restart recovery;
4. malformed headers/payloads and poison events;
5. concurrent duplicate consumption against PostgreSQL;
6. producer/consumer failure and retry boundaries.

Only after that distributed correctness gate is green should the matching domain add driver availability, geospatial candidate lookup, and deterministic assignment state transitions.
