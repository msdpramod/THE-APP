# THE APP Architecture

## Current stage: Foundation 008

THE APP is a modular Spring Boot platform API plus a lightweight customer web shell. Domain contracts, tests, observability, durable mutation semantics, and deployment boundaries are established before services are split across the network.

## Product domains

- **Ride:** quoting, durable retry-safe booking, transactional `RideRequested` capture, leased outbox dispatch, and an acknowledged Kafka transport adapter now; replay-safe driver matching, trip lifecycle, live location, surge pricing, and settlement next.
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
Outbox lease workers -> Kafka gateway -> Kafka -> async domain consumers
                                          |-> matching
                                          |-> notifications
                                          |-> payments
                                          |-> analytics
```

The current codebase does **not** claim million-TPS throughput. It creates the seams required to scale safely: versioned APIs, explicit validation, bounded domains, durable idempotent mutation contracts, transactional event capture, lease-based bounded dispatch, acknowledged broker publication, health probes, metrics, CI gates, and evolution documentation.

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
     Kafka gateway
          |
          | producer acks=all + send acknowledgement
          | key = aggregate/ride id
          | headers: event-id, event-type, aggregate-type
          v
        Kafka
          |
          | success -> PUBLISHED
          ` failure -> PENDING + exponential backoff
                       or FAILED after max attempts

same key + same fingerprint -> persisted booking, no second event
same key + other fingerprint -> 409 Conflict
```

Foundation 008 adds a real Kafka-backed `OutboxDeliveryGateway` without enabling publication by default. `RideRequested` maps to versioned topic `ride.requested.v1`, the ride aggregate ID is the record key to preserve per-ride ordering within Kafka partitions, and the stable outbox event ID is carried as an `event-id` header for downstream deduplication. The gateway waits for the Kafka send acknowledgement before allowing the outbox row to transition to `PUBLISHED`.

Producer configuration defaults to `acks=all` with idempotence enabled. These settings reduce producer-side duplication and data-loss windows but **do not** replace consumer idempotency: a process can still publish successfully and crash before updating the outbox row, causing a later replay. Every side-effecting consumer must therefore deduplicate by stable event ID.

The publisher and Kafka transport remain opt-in (`THE_APP_OUTBOX_PUBLISHER_ENABLED=true` and `THE_APP_OUTBOX_TRANSPORT=kafka`). They should not be enabled in a deployment until broker connectivity and the first consumer's inbox/deduplication path are integration-tested.

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

Outbox workers claim bounded batches with database locks and explicit lease ownership before a broker adapter is added. Delivery is pluggable behind `OutboxDeliveryGateway`.

### ADR-009 — Acknowledged, versioned Kafka publication

Outbox rows are marked `PUBLISHED` only after Kafka acknowledges the send. Event families map to explicit versioned topics, aggregate IDs are stable Kafka keys, and event IDs travel as headers so consumers can deduplicate replayed delivery. Unknown event types fail closed instead of being routed to a catch-all topic.

## Near-term target architecture

1. Replay-safe driver-matching consumer with a transactional inbox/deduplication table keyed by `event-id`.
2. Broker integration tests covering acknowledged publish, duplicate redelivery, consumer restart, and poison messages before enabling publication in deployment profiles.
3. Redis for hot read paths and geospatial driver availability.
4. OpenTelemetry traces, Prometheus metrics, structured logs, and SLO dashboards.
5. Authentication/authorization boundaries for customer, driver, restaurant, and operations roles.
6. Persisted quote/pricing snapshots and payment authorization boundaries.
7. PostgreSQL HA/backup configuration, connection-pool sizing, and staged deployment manifests.
