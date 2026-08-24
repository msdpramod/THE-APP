# Evolution Log

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
- There is no transactional outbox yet, so no durable `RideRequested` event is emitted.
- Authentication and authorization are still absent; `riderId` remains caller-provided demonstration data.
- Database connection-pool sizing, production migrations, backup/restore, encryption, and HA settings are not yet deployment-tested.

### Next evolution target

Add a transactional outbox table and write a `RideRequested` event in the same database transaction as the booking. Add a publisher lease/retry strategy and replay tests before connecting Kafka. This creates an auditable, loss-resistant boundary between synchronous booking and asynchronous driver matching.

## Foundation 003

### Added

- Idempotency-key payload consistency for ride booking creation.
- Reusing the same `Idempotency-Key` with the same request still returns the original booking.
- Reusing the same `Idempotency-Key` with a different rider/pickup/dropoff request now returns HTTP `409 Conflict` instead of silently returning an unrelated prior booking.
- MVC coverage for conflicting idempotency-key reuse.

### Why this shape

Idempotency is only safe when a retry key is bound to the original operation. Returning a previous booking for a materially different request can attach the wrong trip to a caller and becomes especially dangerous once pricing, payment authorization, and driver matching are introduced. Foundation 003 closes that correctness gap before durable persistence and asynchronous processing.

### Known risks / gaps

- Booking and idempotency state are still process-local and disappear on restart.
- Multiple application instances still do not share idempotency state.
- The request comparison is an in-memory structural comparison; durable storage will persist a canonical request fingerprint alongside the idempotency record.
- `riderId` is still a demonstration identifier because authentication is not implemented yet.
- Booking creation does not yet bind to a persisted quote or pricing snapshot.
- No driver matching event is emitted yet.

### Next evolution target

Move booking state and idempotency records into PostgreSQL with Flyway migrations. Persist a canonical request fingerprint, booking, and outbox event in one database transaction. Kafka can then publish replay-safe ride-requested events for asynchronous driver matching without weakening the HTTP contract.

## Foundation 002

### Added

- `POST /api/v1/rides/bookings` mutation contract with required `Idempotency-Key`.
- Atomic in-process idempotency handling so client retries return the original booking instead of creating duplicates.
- `GET /api/v1/rides/bookings/{bookingId}` lookup contract.
- Explicit ride lifecycle states: `REQUESTED`, `MATCHING`, `DRIVER_ASSIGNED`, `DRIVER_ARRIVING`, `IN_PROGRESS`, `COMPLETED`, `CANCELLED`.
- MVC tests covering booking creation, duplicate retry behavior, missing idempotency key, and unknown booking lookup.
- Customer UI flow that turns a quote into a ride request and safely reuses the same idempotency key on retry.

### Why this shape

Mutation safety is more important than adding Kafka prematurely. A ride request is a money- and state-sensitive operation, so THE APP establishes an idempotent HTTP contract before durable storage or asynchronous matching. The API behavior and tests are the durable part of this increment.

### Known risks / gaps

- `riderId` is still a demonstration identifier because authentication is not implemented yet.
- Booking creation does not yet bind to a persisted quote or pricing snapshot.
- No driver matching event is emitted yet.

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

### Why this shape

The repository started empty. The first evolution established a runnable vertical slice and review gates before introducing databases, Kafka, Redis, auth, or service decomposition. This keeps later evolution measurable and reversible.
