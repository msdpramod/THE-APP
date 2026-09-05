# Foundation 013 — Kafka broker acknowledgement gate

## Goal

Move transactional-outbox validation beyond mocked `KafkaTemplate` behavior and prove the production Kafka delivery path against a real broker before expanding matching features.

## What changed

- Added `KafkaOutboxBrokerIntegrationTest`, opt-in with `THE_APP_KAFKA_IT=true`.
- Added a dedicated `backend-kafka` GitHub Actions job using Apache Kafka 4.0.0 in single-node KRaft mode.
- The integration test inserts a real `RideRequested` outbox row, invokes the production `OutboxPublisher` + `KafkaOutboxDeliveryGateway` path, and verifies:
  - the outbox row becomes `PUBLISHED` only after the gateway's broker-acknowledged send returns;
  - exactly one publish attempt is recorded for the tested row;
  - `published_at` is populated and `last_error` is cleared;
  - the broker contains the expected booking key and JSON payload;
  - `event-id`, `event-type`, and `aggregate-type` headers survive the real Kafka round trip.
- Standard backend, PostgreSQL, and frontend lanes remain unchanged.

## Why this is the next gate

Foundation 012 proved replay-safety semantics on PostgreSQL, but the Kafka transport was still validated with mocks. The production gateway blocks on `KafkaTemplate.send(...).get(...)`, and `OutboxPublisher` marks an event `PUBLISHED` only after that call succeeds. A real broker gate validates that contract through Spring Kafka serialization, broker metadata, acknowledgement, and consumer deserialization instead of assuming the mock matches production behavior.

## Risk posture

- No HTTP API, schema, topic name, message format, feature flag default, frontend behavior, or production algorithm changed.
- The scheduled outbox publisher remains disabled by default.
- The matching Kafka listener remains disabled by default.
- The new test is opt-in locally to keep normal `mvn verify` self-contained.
- CI now pulls an Apache Kafka container, increasing CI runtime and introducing a broker-startup dependency in one isolated lane.

## Local validation

With a Kafka broker reachable at `localhost:9092`:

```bash
cd backend/platform-api
THE_APP_KAFKA_IT=true \
THE_APP_KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
mvn -B -Dtest=KafkaOutboxBrokerIntegrationTest test
```

## What this does not prove yet

Foundation 013 intentionally does not claim full end-to-end matching delivery. It does not yet prove:

- duplicate broker redelivery into `DriverMatchingInboxStore` across consumer instances;
- malformed or poison records and dead-letter/recovery policy;
- consumer restart/rebalance behavior;
- PostgreSQL + Kafka in the same integration lane;
- crash timing between broker acknowledgement and the database `PUBLISHED` update.

The last case is a deliberate at-least-once boundary: a process can crash after Kafka accepts an event but before the outbox row is marked `PUBLISHED`, causing a later resend. Downstream inbox idempotency must therefore remain authoritative.

## Next target

Add a broker-to-inbox replay-safety gate using real Kafka plus PostgreSQL. Publish one `RideRequested` event, force duplicate delivery/redelivery, and prove the matching inbox accepts the logical event exactly once. Add fail-closed tests for malformed headers/payloads and define a bounded poison-event recovery/DLT policy before enabling the consumer by default.
