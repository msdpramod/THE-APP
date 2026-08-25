# THE APP Architecture

## Current stage: Foundation 005

THE APP is a modular Spring Boot platform API plus a lightweight customer web shell. Domain contracts, tests, observability, durable mutation semantics, and deployment boundaries are established before services are split across the network.

## Product domains

- **Ride:** quoting plus durable, retry-safe booking and transactional `RideRequested` outbox now; publishing, driver matching, trip lifecycle, live location, surge pricing, and settlement next.
- **Food:** restaurant discovery now; catalog, cart, ordering, kitchen workflow, delivery matching, and settlement next.
- **Shared platform:** identity, payments, wallet, notifications, risk, experimentation, and observability will be introduced behind stable contracts.

## Scaling path

```text
Client apps
    |
Edge / API gateway
    |
Domain APIs  ---- Redis / geo cache
    |
Transactional stores
    |
Outbox / CDC -> Kafka -> async domain consumers
                         |-> matching
                         |-> notifications
                         |-> payments
                         |-> analytics
```

The current codebase does **not** claim million-TPS throughput. It creates the seams required to scale safely: versioned APIs, explicit validation, bounded domains, durable idempotent mutation contracts, transactional event capture, health probes, CI gates, and evolution documentation.

## Ride booking boundary

```text
Customer UI
   |
   | POST /api/v1/rides/bookings
   | Idempotency-Key: <stable retry key>
   v
Ride booking API
   |
   | SHA-256 canonical request fingerprint
   v
Database transaction
   |-- INSERT ride_booking
   `-- INSERT outbox_event(RideRequested, PENDING)
          |
          `-- future leased publisher -> Kafka -> matching

same key + same fingerprint -> persisted booking, no second event
same key + other fingerprint -> 409 Conflict
```

Foundation 005 closes the dual-write gap between accepting a ride and recording the event that will eventually trigger asynchronous matching. A newly accepted booking and its `RideRequested` outbox row are persisted in the same Spring transaction. If either write fails, neither should commit. Replays of the same idempotency key return the existing booking and do not append another event.

The default developer database remains file-backed H2 in PostgreSQL compatibility mode for zero-dependency startup. Production deployments are expected to supply PostgreSQL connection settings with environment variables. Flyway owns schema evolution so the same migration history travels with each environment.

## Decisions

### ADR-001 — Start modular, extract by pressure

Starting with many empty microservices would increase deployment, tracing, schema, and failure complexity without useful load. We will extract a domain into an independently deployable service when at least one of these becomes true:

1. independent scaling is required;
2. a separate availability/SLO boundary is required;
3. team ownership justifies independent release cadence;
4. data consistency or workload characteristics demand isolation.

### ADR-002 — API versioning from day one

Public HTTP contracts live under `/api/v1`. Breaking changes require a new version or a compatibility migration.

### ADR-003 — No open CORS policy

Development CORS is restricted to known localhost origins. Production origins must be supplied explicitly at deployment time.

### ADR-004 — Test before extraction

Every domain receives contract/controller tests before asynchronous messaging or service extraction. Future Kafka flows must include idempotency and replay tests.

### ADR-005 — Idempotency before asynchronous mutation

Retry-sensitive mutation APIs require an idempotency contract before events, payment workflows, or service extraction are introduced. Each key is persisted with a canonical SHA-256 request fingerprint. A unique database constraint provides cross-replica arbitration; conflicting key reuse remains HTTP 409.

### ADR-006 — Database before broker

A broker is not the source of truth for accepting a ride. Booking state and the outbox event commit atomically in the transactional database. Kafka publishing is introduced only after the outbox has lease, retry, replay, and failure tests.

### ADR-007 — At-least-once publication, idempotent consumption

The outbox will be published with at-least-once delivery semantics. Consumers must therefore use stable event IDs and make side effects replay-safe instead of depending on exactly-once network delivery.

## Near-term target architecture

1. Leased outbox publisher with retry/backoff, explicit failure states, and metrics.
2. Kafka publisher and replay-safe driver-matching consumer.
3. Redis for hot read paths and geospatial driver availability.
4. OpenTelemetry traces, Prometheus metrics, structured logs, and SLO dashboards.
5. Authentication/authorization boundaries for customer, driver, restaurant, and operations roles.
6. Persisted quote/pricing snapshots and payment authorization boundaries.
7. PostgreSQL HA/backup configuration and staged deployment manifests.
