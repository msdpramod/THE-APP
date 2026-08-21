# THE APP

THE APP is an evolving full-stack mobility and delivery platform combining ride-hailing and food-delivery experiences in one product.

The codebase evolves in small, reviewable increments. Production-scale capabilities such as event-driven workflows, idempotency, observability, caching, partitioning, and multi-region deployment are added behind tested boundaries instead of being simulated with premature complexity.

## Foundation 001

The first vertical slice contains:

- Java 17 + Spring Boot 4.1.1 platform API.
- `POST /api/v1/rides/quote` for validated ride estimates.
- `GET /api/v1/food/restaurants` for restaurant discovery.
- Responsive customer web UI switching between ride and food experiences.
- Actuator health/info/metrics endpoints and health probes.
- MVC contract tests and GitHub Actions verification.
- A non-root backend Docker image.
- Architecture and evolution documentation.

## Run locally

### Backend

```bash
cd backend/platform-api
mvn spring-boot:run
```

API health: `http://localhost:8080/actuator/health`

### Frontend

In another terminal:

```bash
cd frontend/web
python3 -m http.server 3000
```

Open `http://localhost:3000`.

## Repository layout

```text
THE-APP/
├── backend/
│   └── platform-api/
├── frontend/
│   └── web/
├── docs/
│   ├── ARCHITECTURE.md
│   └── EVOLUTION.md
└── .github/workflows/ci.yml
```

## Evolution principles

- Keep customer, driver, restaurant, and operations experiences coherent.
- Prefer test-gated changes over broad rewrites.
- Document every meaningful architecture decision.
- Design for horizontal scale without claiming unverified throughput.
- Treat security, observability, failure recovery, and developer experience as first-class features.

## Next target

PostgreSQL + Flyway persistence, then an idempotent ride-booking state machine and transactional outbox before Kafka-based driver matching.

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for system boundaries and [`docs/EVOLUTION.md`](docs/EVOLUTION.md) for the change log and known gaps.
