# Evolution Log

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
- No persistence, idempotency, or transactional outbox exists yet.
- No distributed tracing or Prometheus registry has been wired yet.
- Frontend location lookup currently maps a small demonstration set of Hyderabad locations.
- Production CORS configuration and TLS termination are not yet defined.

### Next evolution target

Introduce PostgreSQL persistence with Flyway migrations and an idempotent ride-booking state machine. This should be followed by an outbox table and Kafka publisher so booking events can be replayed safely before driver matching becomes asynchronous.
