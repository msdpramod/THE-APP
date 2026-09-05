# THE APP Architecture

## Current stage: Foundation 009

THE APP is a modular Spring Boot platform API plus a lightweight customer web shell. Domain contracts, tests, observability, durable mutation semantics, and deployment boundaries are established before services are split across the network.

## Product domains

- **Ride:** quoting, durable retry-safe booking, transactional `RideRequested` capture, leased outbox dispatch, acknowledged Kafka transport, and replay-safe driver-matching intake now; actual driver selection, trip lifecycle, live location, surge pricing, and settlement next.
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
                                          |
                                          | RideRequested
                                          v
                                   transactional inbox
                                          |
                                          v
                                  matching request state
```

The current codebase does **not** claim million-TPS throughput. It creates the seams required to scale safely: versioned APIs, explicit validation, durable idempotent mutation contracts, transactional event capture, lease-based bounded dispatch, acknowledged broker publication, replay-safe consumer intake, health probes, metrics, CI gates, and evolution documentation.

## Ride booking, outbox, and inbox boundary

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
          | claim bounded batch / lease
          v
     Kafka gateway
          |
          | acks=all + producer idempotence
          | key = booking id
          | headers: event-id, event-type, aggregate-type
          v
        Kafka
          |
          | at-least-once delivery
          v
RideRequested consumer
          |
          | validate required headers + topic key
          v
Consumer database transaction
   |-- INSERT consumer_inbox(event-id) ON CONFLICT DO NOTHING
   `-- INSERT driver_matching_request(..., PENDING) only for first delivery

same event-id replay -> no second matching request
```

Foundation 009 closes the first consumer-side replay gap. The driver-matching consumer is keyed by the stable `event-id` header and atomically records inbox acceptance with initialization of durable matching state. Duplicate delivery becomes a no-op before any later driver-assignment side effect is allowed.

The Kafka key must match the `RideRequested.bookingId`, and unexpected event types or missing required headers fail closed. This protects per-ride ordering assumptions and prevents malformed records from silently creating matching state.

The publisher, Kafka transport, and matching consumer remain opt-in. `THE_APP_OUTBOX_PUBLISHER_ENABLED=true`, `THE_APP_OUTBOX_TRANSPORT=kafka`, and `THE_APP_MATCHING_CONSUMER_ENABLED=true` should only be enabled together in a deployment after broker-level integration tests verify publish acknowledgement, duplicate redelivery, restart behavior, and poison-message handling.

The default developer database remains file-backed H2 in PostgreSQL compatibility mode for zero-dependency startup. Production deployments are expected to supply PostgreSQL connection settings with environment variables. Flyway owns schema evolution so the same migration history travels with each environment.

## Decisions

### ADR-001 — Start modular, extract by pressure

Starting with many empty microservices would increase deployment, tracing, schema, and failure complexity without useful load. Extract a domain only when independent scaling, an SLO boundary, ownership, or data/workload isolation justifies it.

### ADR-002 — API versioning from day one

Public HTTP contracts live under `/api/v1`. Breaking changes require a new version or a compatibility migration.

### ADR-003 — No open CORS policy

Development CORS is restricted to known localhost origins. Production origins must be supplied explicitly at deployment time.

### ADR-004 — Test before extraction

Every domain receives contract/controller tests before asynchronous messaging or service extraction. Kafka flows require idempotency and replay tests.

### ADR-005 — Idempotency before asynchronous mutation

Retry-sensitive mutation APIs require an idempotency contract before events, payment workflows, or service extraction are introduced. Each key is persisted with a canonical SHA-256 request fingerprint. A unique database constraint provides cross-replica arbitration; conflicting key reuse remains HTTP 409.

### ADR-006 — Database before broker

A broker is not the source of truth for accepting a ride. Booking state and the outbox event commit atomically in the transactional database.

### ADR-007 — At-least-once publication, idempotent consumption

Outbox delivery uses at-least-once semantics. Consumers use stable event IDs and make side effects replay-safe rather than depending on exactly-once network delivery.

### ADR-008 — Lease before transport

Outbox workers claim bounded batches with database locks and explicit lease ownership before broker delivery. Delivery is pluggable behind `OutboxDeliveryGateway`.

### ADR-009 — Acknowledged, versioned Kafka publication

Outbox rows are marked `PUBLISHED` only after Kafka acknowledges the send. Event families map to explicit versioned topics, aggregate IDs are stable Kafka keys, and event IDs travel as headers. Unknown event types fail closed.

### ADR-010 — Transactional inbox before matching side effects

Every side-effecting Kafka consumer first persists the stable event ID in a transactional inbox. The first `RideRequested` delivery atomically creates a durable `PENDING` matching request; replayed deliveries do not repeat that initialization. Driver assignment must build on this boundary rather than consume Kafka directly without deduplication.

## Near-term target architecture

1. Broker integration tests covering acknowledged publish, duplicate redelivery, consumer restart, malformed events, and poison messages.
2. Driver availability and geospatial candidate lookup, likely Redis-backed, with deterministic assignment state transitions.
3. OpenTelemetry traces, Prometheus metrics, structured logs, and SLO dashboards across outbox/Kafka/inbox latency.
4. Authentication/authorization boundaries for customer, driver, restaurant, and operations roles.
5. Persisted quote/pricing snapshots and payment authorization boundaries.
6. PostgreSQL HA/backup configuration, connection-pool sizing, and staged deployment manifests.
7. Food ordering transactional workflow using the same idempotency/outbox/inbox patterns where appropriate.
