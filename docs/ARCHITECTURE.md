# THE APP Architecture

## Current stage: Foundation 004

THE APP is a modular Spring Boot platform API plus a lightweight customer web shell. Domain contracts, tests, observability, durable mutation semantics, and deployment boundaries are established before services are split across the network.

## Product domains

- **Ride:** quoting plus durable, retry-safe idempotent booking now; outbox, driver matching, trip lifecycle, live location, surge pricing, and settlement next.
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

The current codebase does **not** claim million-TPS throughput. It creates the seams required to scale safely: versioned APIs, explicit validation, bounded domains, durable idempotent mutation contracts, health probes, CI gates, and evolution documentation.

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
Transactional database
   |
   |-- first key ----------------------> INSERT REQUESTED booking
   |-- same key + same fingerprint ---> return persisted booking
   `-- same key + other fingerprint --> 409 Conflict

Database UNIQUE(idempotency_key) also arbitrates concurrent replicas.
Next: booking + outbox event in one transaction -> Kafka -> matching
```

Foundation 004 moves retry correctness out of JVM memory and into the transactional store. The default developer database is file-backed H2 in PostgreSQL compatibility mode for zero-dependency startup. Production deployments are expected to supply PostgreSQL connection settings with environment variables. Flyway owns schema evolution so the same migration history travels with each environment.

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

A broker is not the source of truth for accepting a ride. Booking state and the future outbox event must commit atomically in the transactional database. Kafka publishing is introduced only after the outbox has replay and failure tests.

## Near-term target architecture

1. Transactional outbox written in the same transaction as ride booking state.
2. Publisher lease/retry strategy with explicit delivery semantics and replay tests.
3. Kafka publisher and replay-safe driver-matching consumer.
4. Redis for hot read paths and geospatial driver availability.
5. OpenTelemetry traces, Prometheus metrics, structured logs, and SLO dashboards.
6. Authentication/authorization boundaries for customer, driver, restaurant, and operations roles.
7. Persisted quote/pricing snapshots and payment authorization boundaries.
8. PostgreSQL HA/backup configuration and staged deployment manifests.
