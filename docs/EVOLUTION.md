# Evolution Log

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

Mutation safety is more important than adding Kafka prematurely. A ride request is a money- and state-sensitive operation, so THE APP now establishes an idempotent HTTP contract before adding durable storage or asynchronous matching. The current in-memory implementation is intentionally temporary; the API behavior and tests are the durable part of this increment.

### Known risks / gaps

- Booking/idempotency state is process-local and disappears on restart.
- Multiple application instances would not share idempotency state.
- `riderId` is still a demonstration identifier because authentication is not implemented yet.
- Booking creation does not yet bind to a persisted quote or pricing snapshot.
- No driver matching event is emitted yet.

### Next evolution target

Move booking state and idempotency records into PostgreSQL with Flyway migrations. Persist the booking and an outbox event in one database transaction, then publish that event to Kafka for asynchronous driver matching. This removes the single-instance limitation without weakening the tested HTTP contract.

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

The repository started empty. The first evolution establishes a runnable vertical slice and review gates before introducing databases, Kafka, Redis, auth, or service decomposition. This keeps later evolution measurable and reversible.

### Known risks / gaps

- Ride fare logic is intentionally simple and is not production pricing.
- Restaurant data is in-memory seed data.
- No authentication or authorization exists yet.
- No persistence or transactional outbox exists yet.
- No distributed tracing or Prometheus registry has been wired yet.
- Frontend location lookup currently maps a small demonstration set of Hyderabad locations.
- Production CORS configuration and TLS termination are not yet defined.

### Next evolution target

Introduce PostgreSQL persistence with Flyway migrations, durable idempotency, and a transactional outbox before asynchronous Kafka-based driver matching.
