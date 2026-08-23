# THE APP Architecture

## Current stage: Foundation 003

THE APP is a modular Spring Boot platform API plus a lightweight customer web shell. Domain contracts, tests, observability, and deployment boundaries are established before services are split across the network.

## Product domains

- **Ride:** quoting and retry-safe idempotent booking now; durable booking state, driver matching, trip lifecycle, live location, surge pricing, and settlement next.
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

The current codebase does **not** claim million-TPS throughput. It creates the seams required to scale safely: versioned APIs, explicit validation, bounded domains, idempotent mutation contracts, health probes, CI gates, and evolution documentation.

## Ride booking boundary

```text
Customer UI
   |
   | POST /api/v1/rides/bookings
   | Idempotency-Key: <stable retry key>
   v
Ride booking API
   |
   |-- first request ------------------> create REQUESTED booking
   |
   |-- same key + same payload --------> return original booking
   |
   `-- same key + different payload ---> 409 Conflict

Foundation 003: process-local state with request/key binding
Next: PostgreSQL transaction -> fingerprint + booking + outbox event -> Kafka -> matching
```

The idempotency contract is intentionally introduced before persistence. A retry key is now bound to the original ride request, preventing accidental reuse for a different trip. Client retry behavior is stable and test-covered; the storage mechanism can evolve from process memory to PostgreSQL without changing the HTTP contract.

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

Retry-sensitive mutation APIs require an idempotency contract before events, payment workflows, or service extraction are introduced. Foundation 003 binds each key to the original request and rejects conflicting reuse. Durable idempotency moves to PostgreSQL next, where a canonical request fingerprint will be persisted alongside the booking.

## Near-term target architecture

1. PostgreSQL persistence with Flyway migrations for ride booking and idempotency records.
2. Persist a canonical request fingerprint with each idempotency record.
3. Transactional outbox written in the same transaction as booking state.
4. Kafka publisher and replay-safe driver-matching consumer.
5. Redis for hot read paths and geospatial driver availability.
6. OpenTelemetry traces, Prometheus metrics, structured logs, and SLO dashboards.
7. Authentication/authorization boundaries for customer, driver, restaurant, and operations roles.
8. Container images and staged deployment manifests.
