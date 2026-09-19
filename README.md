# SeatSurge

**A flash-sale ticketing engine built with Spring Boot.** It lets 50,000 fans compete for 5,000 seats at the same second without overselling a single one.

> 🚧 Work in progress. See the build phases below.

## Why this project
Ticket drops for big concerts are a classic high-contention problem: many users race for the same inventory, payments are asynchronous, and people abandon checkout halfway. SeatSurge tackles this with:

- **Time-limited seat holds**: a Redis `SET NX` lock is the fast path, and a JPA optimistic lock (`@Version`) on Postgres is the source of truth.
- **Virtual waiting room**: a Redis-backed queue issues short-lived admission tokens.
- **Idempotent Stripe checkout**: `Idempotency-Key` headers plus webhooks that are signature-verified and deduplicated.
- **Transactional outbox**: reliable, at-least-once delivery of ticket issuance emails.
- **Virtual threads (Java 25)**: each request runs on a cheap virtual thread, so it scales to heavy concurrency while the code stays plain blocking Spring MVC + JPA, with no reactive complexity.
- **JWT auth with refresh-token rotation**: refresh tokens are single-use and stored only as hashes; replaying a used one revokes every token that user holds (reuse detection).
- **QR e-tickets**: an atomic gate check-in prevents a ticket from being scanned twice.

## Tech stack
Java 25 · Spring Boot 4.1 · Spring Security + JWT · Spring Data JPA · PostgreSQL 17 · Flyway · Redis · Stripe · springdoc-openapi (Swagger) · Testcontainers · Docker Compose · k6

## Running locally
Prerequisites: JDK 25 and Docker.

```bash
./mvnw spring-boot:run      # starts Postgres, Redis and Mailpit via compose.yaml automatically
```

- Swagger UI: http://localhost:8080/swagger-ui.html
- Health: http://localhost:8080/actuator/health
- Mailpit (dev email inbox): http://localhost:8025

Run the tests (Testcontainers spins up Postgres and Redis):

```bash
./mvnw test
```

### Configuration
| Env var | Purpose |
|---|---|
| `JWT_SECRET` | Base64 256-bit signing key (a dev default is provided) |
| `STRIPE_SECRET_KEY` | Stripe test secret key (`sk_test_...`) |
| `STRIPE_WEBHOOK_SECRET` | Signing secret from `stripe listen` (`whsec_...`) |

## Build phases
- [x] 1. Skeleton: Docker Compose, Flyway schema, error handling (RFC 7807), Swagger, request tracing
- [x] 2. Auth: JWT access/refresh tokens with rotation + reuse detection, roles (FAN, ORGANIZER, GATE_STAFF, ADMIN), virtual threads
- [ ] 3. Catalog: venues, sections, seats, events, price tiers, public search
- [ ] 4. Seat holds: Redis lock + optimistic locking, expiry sweeper, concurrency test
- [ ] 5. Payments: Stripe Checkout, idempotency, webhooks, outbox
- [ ] 6. Tickets: QR codes, gate check-in, transfer, refunds
- [ ] 7. Waiting room: admission tokens, rate limiting
- [ ] 8. Polish: CI, k6 load test, benchmarks
