# Evolution Log

## Foundation 006

### Fixed

- Migrated transactional outbox serialization from Jackson 2 (`com.fasterxml.jackson`) types to Spring Boot 4.1's default Jackson 3 (`tools.jackson`) stack.
- `RideBookingStore` now depends on the auto-configured `JsonMapper` bean and catches Jackson 3's `JacksonException`.
- Kept the ride booking HTTP contract, idempotency behavior, database schema, and outbox payload unchanged.

### Why this shape

The Foundation 005 CI run exposed a backend verification failure while the frontend gate remained green. Spring Boot 4 uses Jackson 3 as its preferred/default JSON implementation, so keeping direct Jackson 2 imports in the outbox serialization path creates an avoidable dependency/API mismatch. This repair restores alignment with the framework before adding the leased publisher.

### Risk posture

- This is intentionally a narrow compatibility repair rather than another architectural expansion.
- No Kafka, scheduler, lease, retry, or status-transition code is added until the corrected backend passes CI.
- JSON payload semantics should remain equivalent, but the build gate is authoritative and the PR remains draft until verification succeeds.

### Next evolution target

After CI is green, implement the leased outbox publisher in a separate reviewable increment: bounded claims, PostgreSQL `FOR UPDATE SKIP LOCKED`, lease expiration/recovery, exponential retry, terminal failure state, Micrometer counters/gauges, and crash/replay integration tests. Kafka connection remains a subsequent step.

## Foundation 005

### Added

- Flyway-managed `outbox_event` table with publishable and aggregate indexes.
- Atomic `RideRequested` event persistence in the same Spring transaction as a newly accepted ride booking.
- JSON event payload containing booking ID, rider, pickup/dropoff, and request timestamp.
- Replay protection: retrying the same idempotent booking does not append a second outbox event.
- Full application test coverage asserting exactly one `RideRequested` event exists for a booking across an HTTP replay.
- PostgreSQL-compatible outbox migration and an explicit at-least-once publication/idempotent-consumer architecture decision.

### Why this shape

Writing a booking and publishing directly to Kafka would create a dual-write failure mode: the database could commit while Kafka fails, leaving a valid ride that is never matched. Foundation 005 records the integration event inside the same transactional boundary as the booking. A later publisher can retry delivery independently without losing accepted work.

### Known risks / gaps

- No outbox publisher exists yet, so events remain `PENDING` in the database.
- Lease ownership, retry/backoff, poison-event handling, retention, and publisher metrics are not implemented yet.
- Kafka and the driver-matching consumer are intentionally not connected until replay behavior is test-covered.
- Authentication/authorization and persisted pricing snapshots remain absent.
- Production PostgreSQL HA, connection-pool sizing, backups, encryption, and migration rollout are not deployment-tested.

### Next evolution target

Implement a leased outbox publisher with bounded batches, `SKIP LOCKED` semantics for PostgreSQL, retry/backoff, explicit terminal failure handling, and Micrometer metrics. Test crash/replay behavior before adding Kafka. After that, publish stable event IDs to Kafka and make driver matching idempotent by event ID.

## Foundation 004

### Added

- Durable ride booking persistence through Spring JDBC.
- Flyway-managed `ride_booking` schema with a database-level unique `Idempotency-Key` constraint.
- Persisted SHA-256 request fingerprints so retry identity survives process restarts and can be shared across replicas using the same database.
- Duplicate-key race handling: concurrent requests for the same idempotency key converge on the database winner and preserve the existing replay/conflict contract.
- Local durable H2 development storage in PostgreSQL compatibility mode, with environment-variable overrides for a PostgreSQL deployment.
- Full application MockMvc coverage for durable booking creation, replay, conflicting payload reuse, oversized keys, and lookup behavior.
- Correct Spring Boot 4.1 MVC test imports across ride and food tests.

### Why this shape

The previous in-memory idempotency maps were correct only inside one JVM. That is not sufficient for horizontal scaling or restart safety. Foundation 004 moves correctness to the transactional store before introducing Kafka. The HTTP contract stays unchanged while the implementation becomes restart-safe and compatible with multiple application instances sharing one database.

### Known risks / gaps

- The default developer database is file-backed H2 for zero-dependency local startup; production must provide PostgreSQL connection settings through `THE_APP_DB_*` environment variables.
- Booking creation still does not persist the ride quote or pricing snapshot.
- Authentication and authorization are still absent; `riderId` remains caller-provided demonstration data.
- Database connection-pool sizing, production migrations, backup/restore, encryption, and HA settings are not yet deployment-tested.

## Foundation 003

### Added

- Idempotency-key payload consistency for ride booking creation.
- Reusing the same `Idempotency-Key` with the same request still returns the original booking.
- Reusing the same `Idempotency-Key` with a different rider/pickup/dropoff request now returns HTTP `409 Conflict` instead of silently returning an unrelated prior booking.
- MVC coverage for conflicting idempotency-key reuse.

### Why this shape

Idempotency is only safe when a retry key is bound to the original operation. Returning a previous booking for a materially different request can attach the wrong trip to a caller and becomes especially dangerous once pricing, payment authorization, and driver matching are introduced. Foundation 003 closes that correctness gap before durable persistence and asynchronous processing.

## Foundation 002

### Added

- `POST /api/v1/rides/bookings` mutation contract with required `Idempotency-Key`.
- Atomic in-process idempotency handling so client retries return the original booking instead of creating duplicates.
- `GET /api/v1/rides/bookings/{bookingId}` lookup contract.
- Explicit ride lifecycle states: `REQUESTED`, `MATCHING`, `DRIVER_ASSIGNED`, `DRIVER_ARRIVING`, `IN_PROGRESS`, `COMPLETED`, `CANCELLED`.
- MVC tests covering booking creation, duplicate retry behavior, missing idempotency key, and unknown booking lookup.
- Customer UI flow that turns a quote into a ride request and safely reuses the same idempotency key on retry.

## Foundation 001

### Added

- Spring Boot 4.1.1 / Java 17 platform API.
- Versioned ride quote endpoint with coordinate validation and deterministic fare estimation.
- Versioned food restaurant discovery endpoint.
- Focused MVC tests for ride and food HTTP contracts.
- Actuator health, info, metrics, and Kubernetes-style health probes.
- Explicit development CORS origins; no wildcard policy.
- Responsive customer web shell with ride and food modes wired to backend APIs.
- GitHub Actions verification gates for backend and frontend syntax.
- Non-root backend Docker image.
- Architecture decisions and extraction criteria.
