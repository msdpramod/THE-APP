# THE APP Architecture

## Current stage: Foundation 007

THE APP is a modular Spring Boot platform API plus a lightweight customer web shell. Domain contracts, tests, observability, durable mutation semantics, and deployment boundaries are established before services are split across the network.

## Product domains

- **Ride:** quoting, durable retry-safe booking, transactional `RideRequested` capture, and leased outbox dispatch infrastructure now; Kafka publication, driver matching, trip lifecycle, live location, surge pricing, and settlement next.
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
Outbox lease workers -> delivery gateway -> Kafka -> async domain consumers
                                             |-> matching
                                             |-> notifications
                                             |-> payments
                                             |-> analytics
```

The current codebase does **not** claim million-TPS throughput. It creates the seams required to scale safely: versioned APIs, explicit validation, bounded domains, durable idempotent mutation contracts, transactional event capture, lease-based bounded dispatch, health probes, metrics, CI gates, and evolution documentation.

## Ride booking and outbox boundary

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
          | claim bounded batch
          | FOR UPDATE SKIP LOCKED
          | status -> IN_FLIGHT + lease owner/expiry
          v
     delivery gateway
          |
          | success -> PUBLISHED
          ` failure -> PENDING + exponential backoff
                       or FAILED after max attempts

same key + same fingerprint -> persisted booking, no second event
same key + other fingerprint -> 409 Conflict
```

Foundation 007 adds worker-safe outbox leasing without pretending a broker is already integrated. Claiming is bounded and transactionally locked with `FOR UPDATE SKIP LOCKED`, ownership is persisted, stale work can become claimable after lease expiry, delivery failures back off exponentially, poison events become terminal `FAILED`, and Micrometer counters track claims, publishes, retries, and terminal failures.

The scheduled publisher is **disabled by default**. Enabling it requires a real `OutboxDeliveryGateway` implementation. This prevents development or production from silently marking events delivered before Kafka or another durable transport exists.

The default developer database remains file-backed H2 in PostgreSQL compatibility mode for zero-dependency startup. Production deployments are expected to supply PostgreSQL connection settings with environment variables. Flyway owns schema evolution so the same migration history travels with each environment.

## Decisions

### ADR-001 — Start modular, extract by pressure

Starting with many empty microservices would increase deployment, tracing, schema, and failure complexity without useful load. Extract a domain only when independent scaling, an SLO boundary, ownership, or data/workload isolation justifies it.

### ADR-002 — API versioning from day one

Public HTTP contracts live under `/api/v1`. Breaking changes require a new version or a compatibility migration.

### ADR-003 — No open CORS policy

Development CORS is restricted to known localhost origins. Production origins must be supplied explicitly at deployment time.

### ADR-004 — Test before extraction

Every domain receives contract/controller tests before asynchronous messaging or service extraction. Future Kafka flows must include idempotency and replay tests.

### ADR-005 — Idempotency before asynchronous mutation

Retry-sensitive mutation APIs require an idempotency contract before events, payment workflows, or service extraction are introduced. Each key is persisted with a canonical SHA-256 request fingerprint. A unique database constraint provides cross-replica arbitration; conflicting key reuse remains HTTP 409.

### ADR-006 — Database before broker

A broker is not the source of truth for accepting a ride. Booking state and the outbox event commit atomically in the transactional database.

### ADR-007 — At-least-once publication, idempotent consumption

Outbox delivery uses at-least-once semantics. Consumers must use stable event IDs and make side effects replay-safe rather than depending on exactly-once network delivery.

### ADR-008 — Lease before transport

Outbox workers claim bounded batches with database locks and explicit lease ownership before a broker adapter is added. Delivery is pluggable behind `OutboxDeliveryGateway`, and the scheduler remains disabled until a durable transport implementation exists.

## Near-term target architecture

1. Kafka `OutboxDeliveryGateway` with stable event IDs and topic contracts.
2. Replay-safe driver-matching consumer with an inbox/deduplication table.
3. Redis for hot read paths and geospatial driver availability.
4. OpenTelemetry traces, Prometheus metrics, structured logs, and SLO dashboards.
5. Authentication/authorization boundaries for customer, driver, restaurant, and operations roles.
6. Persisted quote/pricing snapshots and payment authorization boundaries.
7. PostgreSQL HA/backup configuration, connection-pool sizing, and staged deployment manifests.
