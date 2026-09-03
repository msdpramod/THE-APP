# Foundation 014 — Kafka-to-PostgreSQL replay safety gate

## Goal

Prove the real driver-matching Kafka consumer and the real PostgreSQL inbox remain replay-safe when the broker delivers the same `RideRequested` event more than once.

Foundation 013 proved the outbox publisher waits for Kafka acknowledgement before marking an event `PUBLISHED`. That still leaves the intentional at-least-once boundary: a broker-acknowledged event can be delivered again after retries, consumer restarts, or a crash between external side effects and offset progress.

## Change

Added `KafkaPostgresDriverMatchingReplayIntegrationTest`, enabled only when `THE_APP_KAFKA_POSTGRES_IT=true`.

The test:

1. starts the production `RideRequestedKafkaConsumer`,
2. publishes a valid `RideRequested` record to a real Kafka broker,
3. waits until PostgreSQL contains the corresponding inbox and matching rows,
4. publishes the exact same event ID and booking again,
5. publishes a sentinel event on the same Kafka partition and waits for it to persist, proving the replay was consumed before assertions,
6. verifies the replayed event still owns exactly one `consumer_inbox` row and exactly one `driver_matching_request`,
7. verifies the surviving matching request remains `PENDING` and the inbox aggregate points to the expected booking.

A new `backend-kafka-postgres` CI lane runs Apache Kafka 4.0.0 and PostgreSQL 16 together and executes only this integration test. Existing standard backend, PostgreSQL-only, Kafka-only, and frontend lanes remain separate.

## Why this is high value

The application intentionally uses at-least-once Kafka delivery. Exactly-once business behavior therefore depends on idempotent consumers, not on hoping the broker sends a record once. Testing the real listener plus the real database is stronger than separately testing Kafka transport and PostgreSQL uniqueness.

This gate validates the current transactional inbox design before driver availability, geospatial matching, or assignment side effects are added. Those later steps would make duplicate processing materially more expensive.

## Compatibility and risk

- No HTTP API changes.
- No database migration changes.
- No Kafka topic, key, payload, or header contract changes.
- No production feature flags are enabled by default.
- No frontend behavior changes.
- Runtime production code is unchanged.
- CI cost increases because one lane starts both Kafka and PostgreSQL.
- This test validates duplicate delivery of a valid event; poison-event retry/DLT policy is still intentionally unresolved.

## Validation

Foundation 013 is green on commit `cc5319c141f323022ef7bb92d5d9a7012280c52b`.

Foundation 014 should be considered complete only when all CI lanes, including `backend-kafka-postgres`, are green on the latest PR head.

## Next target

Define bounded poison-event handling for malformed Kafka records. Add an explicit retry budget and dead-letter topic behavior, then verify malformed headers, unsupported event types, invalid payloads, and key/payload mismatches cannot block the matching partition indefinitely. Keep the matching consumer disabled by default until that failure policy is tested with a real broker.
