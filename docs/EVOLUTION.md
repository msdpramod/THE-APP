# Evolution Log

## Foundation 007

### Added

- Flyway migration for outbox lease ownership, lease expiry, and bounded last-error diagnostics.
- `OutboxLeaseStore` with bounded `FOR UPDATE SKIP LOCKED` claims so multiple workers can divide ready work without processing the same row concurrently.
- Explicit `IN_FLIGHT`, `PUBLISHED`, retryable `PENDING`, and terminal `FAILED` transitions guarded by lease ownership.
- Exponential retry backoff with a configurable maximum-attempt policy.
- Micrometer counters for claimed, published, retried, and terminally failed events.
- `OutboxDeliveryGateway` transport boundary plus a scheduled publisher that remains disabled by default until a durable gateway such as Kafka is supplied.
- Integration coverage for bounded claims, lease-owner protection, retry, and poison-event terminal failure.

### Why this shape

Foundation 006 restored a green build, so the next highest-value step is making the transactional outbox safe to run concurrently before introducing Kafka. The database remains the coordination point: workers lock a bounded set of ready rows, persist lease ownership, and perform delivery outside the claim transaction. Success and failure updates are accepted only from the worker that owns the lease.

The scheduler is intentionally disabled by default. There is no fake logging transport that marks an event delivered. Enabling publication without a real `OutboxDeliveryGateway` should fail configuration rather than lose work silently.

### Risk posture

- Publication remains at-least-once. A process can deliver an event and crash before marking it `PUBLISHED`; the eventual Kafka consumer must therefore deduplicate by stable event ID.
- `FOR UPDATE SKIP LOCKED` is chosen for PostgreSQL production semantics and is exercised through H2 PostgreSQL compatibility mode in CI; production PostgreSQL concurrency/load testing is still required.
- Retry backoff is bounded exponentially, but jitter and operational re-drive tooling are not yet implemented.
- Terminal `FAILED` events require future alerting and an operator re-drive path.
- Authentication, pricing snapshots, payments, geospatial matching, distributed tracing, and production HA remain outside this increment.

### Next evolution target

Implement a Kafka-backed `OutboxDeliveryGateway` that publishes the stable outbox `event_id` as the message identity, define topic/versioning conventions, and add a replay-safe driver-matching consumer with an inbox/deduplication table. Add broker integration tests before enabling the scheduled publisher in a deployment profile.

## Foundation 006

### Fixed

- Migrated transactional outbox serialization from Jackson 2 (`com.fasterxml.jackson`) types to Spring Boot 4.1's default Jackson 3 (`tools.jackson`) stack.
- `RideBookingStore` now depends on the auto-configured `JsonMapper` bean and catches Jackson 3's `JacksonException`.
- Added the focused `spring-boot-starter-webmvc-test` test dependency required by Spring Boot 4.1 for `@WebMvcTest` and `@AutoConfigureMockMvc`.
- Replaced the bare Flyway core dependency with Spring Boot 4.1's `spring-boot-starter-flyway`, restoring Boot's Flyway auto-configuration so migrations execute before JDBC-backed booking tests.
- Added cascaded `@Valid` markers to nested ride quote pickup/dropoff points so coordinate constraints are actually enforced at the HTTP boundary.
- Kept the ride booking HTTP contract, idempotency behavior, database schema, and outbox payload unchanged.

### Why this shape

The Foundation 005 CI run exposed a chain of Spring Boot 4 migration gaps rather than a reason to weaken the tests. First, outbox serialization still used Jackson 2 APIs. Once that compiled, CI exposed the missing focused MVC test module. Once tests compiled, execution exposed that Flyway's Boot 4 auto-configuration module was absent and that nested request records were not being cascaded through Bean Validation. Foundation 006 repairs each framework boundary while preserving the existing product contract.

### Risk posture

- This remains a compatibility and correctness repair rather than another distributed-systems expansion.
- No Kafka, scheduler, lease, retry, or status-transition code is added until the corrected backend passes CI.
- JSON payload semantics and public HTTP behavior remain unchanged except that invalid nested coordinates now correctly return a client error as originally documented and tested.
- The build gate is authoritative and the PR remains draft until verification succeeds.

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
