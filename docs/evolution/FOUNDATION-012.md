# Foundation 012 — PostgreSQL replay-safety gate

## Goal

Validate the driver-matching inbox correctness boundary on a real PostgreSQL engine instead of relying only on H2 PostgreSQL compatibility mode.

## What changed

- Added `PostgresDriverMatchingInboxStoreTest`, enabled only when `THE_APP_POSTGRES_IT=true`.
- Added a dedicated GitHub Actions `backend-postgres` job backed by `postgres:16-alpine`.
- The PostgreSQL lane runs Flyway migrations through the normal Spring Boot startup path and validates:
  - sequential duplicate delivery returns `ACCEPTED` then `DUPLICATE`;
  - concurrent delivery of the same event produces exactly one accepted delivery and one duplicate;
  - exactly one inbox row and one matching request survive concurrent replay;
  - a distinct event ID targeting an already-initialized booking remains an integrity failure;
  - the failed distinct event does not leave an orphan inbox row.

## Why this is the next safe step

Foundation 010 made database uniqueness the authoritative replay-safety boundary, and Foundation 011 proved the race path on H2. H2 compatibility mode is useful for fast feedback, but it cannot prove PostgreSQL locking, unique-constraint, transaction rollback, or Flyway behavior. This gate closes that specific uncertainty without widening production scope.

## Compatibility and risk

- No HTTP API changed.
- No Kafka record contract changed.
- No database migration changed.
- No production feature flag changed.
- No frontend behavior changed.
- The PostgreSQL test is opt-in locally, so normal `mvn verify` remains fast and self-contained.
- CI adds one PostgreSQL service container and therefore modest runtime/image-pull overhead.

## Local validation

With PostgreSQL available on port 5432:

```bash
THE_APP_POSTGRES_IT=true \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/theapp_test \
SPRING_DATASOURCE_USERNAME=theapp \
SPRING_DATASOURCE_PASSWORD=theapp \
SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver \
mvn -Dtest=PostgresDriverMatchingInboxStoreTest test
```

## Next target

Add a Kafka-compatible integration lane for the transactional outbox transport. The first broker-backed gate should prove that a leased outbox event is marked `PUBLISHED` only after broker acknowledgement, that redelivery remains replay-safe at the matching inbox, and that malformed/poison records fail closed without corrupting inbox or matching state.

Only after PostgreSQL + broker correctness is green should the platform introduce driver availability, geospatial candidate lookup, and deterministic assignment transitions.
