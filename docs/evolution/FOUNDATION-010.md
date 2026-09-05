# Foundation 010 — Portable transactional inbox dedupe

## What changed

- Removed PostgreSQL-specific `INSERT ... ON CONFLICT DO NOTHING` from the driver-matching inbox path after Foundation 009 exposed an H2 compatibility failure in CI.
- Split replay classification from the atomic inbox/matching write. `DriverMatchingInboxStore` now checks replay identity outside the write transaction and delegates first-delivery persistence to `DriverMatchingInboxTransaction`.
- Kept inbox acceptance and `PENDING` matching initialization in one transaction with ordinary SQL that works across H2 and PostgreSQL.
- Preserved race safety: if a concurrent duplicate wins after the pre-check, the losing transaction rolls back on the unique constraint; the outer store then verifies that the exact `event-id` exists and returns `DUPLICATE`.
- Added regression coverage proving a distinct event targeting an already-initialized booking is not silently misclassified as a replay and leaves no orphan inbox row.

## Why

Foundation 009 claimed that PostgreSQL `ON CONFLICT` was exercised through H2 PostgreSQL mode. CI proved that assumption false on H2 2.4.240. The failure was useful: database compatibility must be demonstrated by executable tests, not inferred from compatibility-mode naming.

The replacement keeps correctness at the database uniqueness boundary without depending on vendor-specific upsert syntax. A duplicate of the same event becomes a no-op, while a different event that collides on another invariant remains an error. This distinction matters once matching begins to perform driver-assignment side effects.

## Risk posture

- The consumer remains disabled by default.
- The pre-check is an optimization, not the correctness mechanism; the unique key remains authoritative under races.
- Concurrent duplicate behavior is structurally protected by rollback plus post-failure identity verification, but a dedicated concurrent integration test is still desirable.
- H2 remains a developer/CI convenience. Production PostgreSQL and broker-level integration tests are still required before enabling the asynchronous pipeline.
- No API or Kafka message contract changed.

## Next target

Add broker-level integration tests with a real ephemeral Kafka-compatible broker and PostgreSQL-backed consumer state. Cover acknowledged publication, duplicate redelivery, restart recovery, malformed records, poison-event handling, and concurrent duplicate consumption. Only after that gate is green should driver availability, geospatial candidate lookup, and deterministic assignment transitions be introduced.
