# Foundation 015 — Kafka poison-event containment

## Goal

Prevent one permanently invalid `RideRequested` record from blocking the driver-matching Kafka partition indefinitely while preserving bounded retries for transient processing failures.

## What changed

- Added `InvalidRideRequestedEventException` to distinguish permanently invalid broker records from transient infrastructure or persistence failures.
- `RideRequestedKafkaConsumer` now classifies missing required headers, unsupported event types, malformed JSON, and booking-key mismatches as permanent poison records.
- Added a Spring Kafka `CommonErrorHandler` backed by `DeadLetterPublishingRecoverer`.
- Permanent poison records are not retried and are published to a configurable dead-letter topic.
- Other listener failures use a configurable bounded `FixedBackOff` before dead-letter recovery.
- Added configuration for dead-letter topic, retry delay, and maximum retries. Matching consumption remains disabled by default.
- Added a real Kafka + PostgreSQL integration test proving a malformed record reaches the DLT and a following valid record on the same partition still reaches durable inbox and matching state.
- Extended the existing combined Kafka/PostgreSQL CI lane to run both replay-safety and poison-event containment gates.

## Defaults

- DLT: `ride.requested.v1.dlt`
- Retry backoff: 1000 ms
- Maximum retries: 2 (three total delivery attempts for retryable failures)
- Permanent invalid-envelope/payload failures: zero retries before DLT recovery

All values are externally configurable.

## Why this is the next safety boundary

Foundation 014 proved duplicate broker delivery does not create duplicate logical matching work. That still left a partition-liveness failure mode: malformed records throw from the listener. Without an explicit recovery policy, repeated delivery can pin the consumer to the same bad offset.

Foundation 015 contains that failure. Invalid records are preserved for investigation in the DLT, while normal traffic behind them can continue.

## Compatibility and risk

- No HTTP API, ride quote, booking, food, database schema, outbox payload, event topic, event key, or required event header changed.
- The matching consumer remains opt-in and disabled by default.
- DLT publishing introduces another broker write on permanent/retry-exhausted failure paths.
- If the DLT itself is unavailable or misconfigured, recovery can still fail; production alerting and DLT operational ownership remain necessary.
- A transient dependency outage that exceeds the bounded retry budget moves the event to DLT instead of blocking the partition. This intentionally favors partition availability and operator recovery over unbounded in-place retry.

## Validation gate

The combined Kafka/PostgreSQL CI lane must prove:

1. duplicate `RideRequested` delivery still creates one inbox row and one matching request;
2. malformed JSON is published to the configured DLT;
3. the poison event creates no inbox or matching state;
4. a valid event immediately following the poison event on the same partition is processed successfully.

## Next evolution target

Add DLT observability and controlled redrive semantics before enabling matching consumption by default:

- counters for consumed, accepted, duplicate, rejected, retry-exhausted, and dead-lettered records;
- structured logs carrying event ID, topic, partition and offset without leaking payloads;
- health/alert guidance for DLT growth and consumer lag;
- an idempotent, operator-controlled redrive path that reuses the existing inbox dedupe guarantees.

After those operational controls are green, begin driver availability and geospatial candidate selection behind disabled-by-default feature flags.
