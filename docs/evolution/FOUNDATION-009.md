# Foundation 009 — Replay-safe driver matching intake

## What changed

- Added Flyway migration `V4__driver_matching_inbox.sql` with a durable `consumer_inbox` keyed by Kafka `event-id` and a durable `driver_matching_request` table.
- Added `RideRequestedKafkaConsumer`, disabled by default, for versioned `ride.requested.v1` messages.
- Added strict envelope validation: `event-id` and `event-type` headers are mandatory, `event-type` must be `RideRequested`, and the Kafka record key must equal the payload booking ID.
- Added `DriverMatchingInboxStore` so first delivery records inbox acceptance and initializes `PENDING` matching state in one database transaction.
- Duplicate delivery of the same event ID is a database no-op and does not create a second matching request.
- Added integration coverage for duplicate replay and initial matching state.
- Added environment-driven consumer enablement/group configuration while keeping the consumer off by default.

## Why

The outbox publisher intentionally provides at-least-once delivery. A broker acknowledgement followed by a producer crash can cause the same outbox event to be replayed. Driver assignment is a side effect where replay can be costly, so deduplication must exist before matching logic is allowed to act.

The inbox is therefore the correctness boundary. The stable producer event ID, not Kafka offset or process memory, defines message identity. Inbox acceptance and initialization of matching state share one database transaction so a crash cannot commit one without the other.

## Risk posture

- The new consumer remains disabled until broker-level integration tests prove publish/consume interoperability, duplicate redelivery, restart recovery, and poison-message behavior.
- `INSERT ... ON CONFLICT DO NOTHING` is designed for PostgreSQL production semantics and is exercised under H2 PostgreSQL compatibility mode in CI; production PostgreSQL integration/load testing remains required.
- The matching request is only initialized to `PENDING`; no driver is selected yet, so this increment avoids introducing geospatial or assignment side effects before the replay boundary is proven.
- Retry/DLT behavior for malformed or persistently failing Kafka records is not yet operationally configured.
- Authentication, authorization, schema registry, TLS/SASL/ACLs, distributed tracing, and production deployment topology remain future work.

## Next target

Add broker-level integration tests, ideally with a real ephemeral Kafka-compatible broker, covering acknowledged publication, duplicate redelivery, consumer restart, malformed records, and poison-event recovery. Once that gate is green, introduce driver availability/geospatial candidate lookup and deterministic assignment state transitions on top of the transactional inbox.
