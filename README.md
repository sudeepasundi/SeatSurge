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

## API overview (so far)
| Area | Endpoints |
|---|---|
| Auth | `POST /api/v1/auth/{register,login,refresh,logout}`, `GET /api/v1/users/me` |
| Venues (organizer) | `POST/GET /api/v1/venues`, `GET/PUT /api/v1/venues/{id}`, `POST /api/v1/venues/{id}/sections`, `DELETE /api/v1/venues/{id}/sections/{sectionId}` |
| Events (organizer) | `POST /api/v1/events`, `PUT/DELETE /api/v1/events/{id}`, `POST /api/v1/events/{id}/{publish,cancel}`, `GET /api/v1/events/mine` |
| Events (public) | `GET /api/v1/events?q=&city=&category=&from=&to=`, `GET /api/v1/events/{id}`, `GET /api/v1/events/{id}/seats` |
| Seat holds (fan) | `POST /api/v1/events/{id}/holds`, `GET /api/v1/holds`, `GET/DELETE /api/v1/holds/{id}` |
| Orders & checkout (fan) | `POST /api/v1/holds/{id}/checkout` (Idempotency-Key), `GET /api/v1/orders`, `GET /api/v1/orders/{id}` |
| Webhooks | `POST /api/v1/webhooks/stripe` (Stripe-Signature) |

## How overselling is prevented
1. **Redis fast path**: each requested seat is locked with `SET NX PX` (TTL = hold duration). Contended requests are rejected in about 1 ms without opening a DB transaction. Locks are released with a compare-and-delete Lua script so a request never frees a lock it does not own.
2. **Postgres is the source of truth**: in one transaction the seats flip `AVAILABLE -> HELD`, guarded by a JPA `@Version` column. If two transactions race for a seat, one gets an optimistic-lock failure and the whole hold rolls back (all-or-nothing).
3. **One active hold per fan per event**: enforced by a partial unique index, so parallel requests from the same fan cannot get around the ticket limit.
4. **Expiry**: a sweeper puts timed-out holds back on sale using compare-and-set status updates, so it is safe to run on multiple instances.
5. **Degrades gracefully**: if Redis is down, holds still work in Postgres-only mode.

**Verified by tests**: 200 fans on virtual threads race for 10 seats and exactly 10 win. With overlapping 3-seat requests, no seat is ever double-held and no partial hold is left behind. Both scenarios pass with and without Redis. Removing `@Version` makes the Postgres-only run oversell (13 winners for 10 seats), which shows the test catches the bug it guards against.

## Payment flow
```
hold (10 min) --checkout--> order PENDING + Stripe session (hold extended to cover the 30-min session)
   webhook checkout.session.completed --> hold CONVERTED, seats SOLD, tickets issued, ORDER_PAID -> outbox -> email
   webhook checkout.session.expired   --> order CANCELLED, seats back on sale
   paid, but hold already lost        --> order REFUND_PENDING -> outbox -> Stripe refund -> REFUNDED
```
- **Idempotency**: `Idempotency-Key` on checkout replays the stored response. Orders are unique per hold, and Stripe calls carry their own idempotency keys, so retries never double-charge.
- **Webhooks**: HMAC signature and timestamp are verified, and events are deduplicated on the Stripe event id in the same transaction as the state change.
- **Transactional outbox**: side effects (emails, refunds) are written in the same transaction as the business change, then delivered at least once by a poller. The poller claims rows with `FOR UPDATE SKIP LOCKED` leases and retries with exponential backoff, so it is safe on multiple instances.

### Trying it with real Stripe (test mode)
```bash
stripe listen --forward-to localhost:8080/api/v1/webhooks/stripe   # prints whsec_...
STRIPE_SECRET_KEY=sk_test_... STRIPE_WEBHOOK_SECRET=whsec_... ./mvnw spring-boot:run
```
Open the `checkoutUrl` from the checkout response and pay with card `4242 4242 4242 4242`. The ticket email appears in Mailpit (http://localhost:8025).

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
- [x] 3. Catalog: venues, sections with bulk seat generation, draft -> published -> cancelled events, price tiers, public search, live seat map
- [x] 4. Seat holds: Redis SET NX fast path + JPA optimistic locking, one active hold per fan (partial unique index), per-fan ticket limit, expiry sweeper, 200-thread race tests
- [x] 5. Payments: Stripe Checkout (created outside DB transactions), Idempotency-Key replay, signature-verified + deduplicated webhooks, automatic refund of late payments, lease-based transactional outbox (emails, refunds)
- [ ] 6. Tickets: QR codes, gate check-in, transfer, refunds
- [ ] 7. Waiting room: admission tokens, rate limiting
- [ ] 8. Polish: CI, k6 load test, benchmarks
