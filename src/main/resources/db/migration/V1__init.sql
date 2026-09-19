-- SeatSurge initial schema

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    full_name     VARCHAR(120) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE refresh_tokens (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);

-- ---------- Catalog ----------

CREATE TABLE venues (
    id           BIGSERIAL PRIMARY KEY,
    organizer_id BIGINT       NOT NULL REFERENCES users (id),
    name         VARCHAR(150) NOT NULL,
    address      VARCHAR(255) NOT NULL,
    city         VARCHAR(100) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_venues_city ON venues (lower(city));

CREATE TABLE sections (
    id       BIGSERIAL PRIMARY KEY,
    venue_id BIGINT       NOT NULL REFERENCES venues (id) ON DELETE CASCADE,
    name     VARCHAR(100) NOT NULL,
    UNIQUE (venue_id, name)
);

CREATE TABLE venue_seats (
    id          BIGSERIAL PRIMARY KEY,
    section_id  BIGINT      NOT NULL REFERENCES sections (id) ON DELETE CASCADE,
    row_label   VARCHAR(10) NOT NULL,
    seat_number INT         NOT NULL,
    UNIQUE (section_id, row_label, seat_number)
);

CREATE TABLE events (
    id                   BIGSERIAL PRIMARY KEY,
    venue_id             BIGINT       NOT NULL REFERENCES venues (id),
    organizer_id         BIGINT       NOT NULL REFERENCES users (id),
    title                VARCHAR(200) NOT NULL,
    artist               VARCHAR(150) NOT NULL,
    description          TEXT,
    category             VARCHAR(50),
    starts_at            TIMESTAMPTZ  NOT NULL,
    sale_starts_at       TIMESTAMPTZ  NOT NULL,
    status               VARCHAR(20)  NOT NULL,
    max_tickets_per_user INT          NOT NULL DEFAULT 6,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_events_status_starts ON events (status, starts_at);

CREATE TABLE price_tiers (
    id          BIGSERIAL PRIMARY KEY,
    event_id    BIGINT      NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    name        VARCHAR(50) NOT NULL,
    price_cents BIGINT      NOT NULL CHECK (price_cents >= 0),
    currency    CHAR(3)     NOT NULL DEFAULT 'usd',
    UNIQUE (event_id, name)
);

-- ---------- Inventory & holds ----------

CREATE TABLE holds (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id),
    event_id   BIGINT      NOT NULL REFERENCES events (id),
    status     VARCHAR(20) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_holds_status_expires ON holds (status, expires_at);
CREATE INDEX idx_holds_user_event ON holds (user_id, event_id);

CREATE TABLE event_seats (
    id            BIGSERIAL PRIMARY KEY,
    event_id      BIGINT      NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    venue_seat_id BIGINT      NOT NULL REFERENCES venue_seats (id),
    price_tier_id BIGINT      NOT NULL REFERENCES price_tiers (id),
    status        VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    hold_id       BIGINT REFERENCES holds (id),
    version       BIGINT      NOT NULL DEFAULT 0,
    UNIQUE (event_id, venue_seat_id)
);
CREATE INDEX idx_event_seats_event_status ON event_seats (event_id, status);
CREATE INDEX idx_event_seats_hold ON event_seats (hold_id);

-- ---------- Orders, payments, tickets ----------

CREATE TABLE orders (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES users (id),
    event_id     BIGINT      NOT NULL REFERENCES events (id),
    hold_id      BIGINT      NOT NULL UNIQUE REFERENCES holds (id),
    amount_cents BIGINT      NOT NULL,
    currency     CHAR(3)     NOT NULL,
    status       VARCHAR(20) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_orders_user ON orders (user_id);

CREATE TABLE payments (
    id                       BIGSERIAL PRIMARY KEY,
    order_id                 BIGINT       NOT NULL UNIQUE REFERENCES orders (id),
    stripe_session_id        VARCHAR(255) UNIQUE,
    stripe_payment_intent_id VARCHAR(255),
    checkout_url             TEXT,
    status                   VARCHAR(20)  NOT NULL,
    amount_cents             BIGINT       NOT NULL,
    currency                 CHAR(3)      NOT NULL,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE tickets (
    id              BIGSERIAL PRIMARY KEY,
    order_id        BIGINT      NOT NULL REFERENCES orders (id),
    event_id        BIGINT      NOT NULL REFERENCES events (id),
    event_seat_id   BIGINT      NOT NULL UNIQUE REFERENCES event_seats (id),
    owner_id        BIGINT      NOT NULL REFERENCES users (id),
    code            UUID        NOT NULL UNIQUE,
    status          VARCHAR(20) NOT NULL,
    checked_in_at   TIMESTAMPTZ,
    checked_in_by   BIGINT REFERENCES users (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_tickets_owner ON tickets (owner_id);

-- ---------- Reliability ----------

CREATE TABLE idempotency_keys (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users (id),
    idem_key        VARCHAR(100) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    response_status INT,
    response_body   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (user_id, idem_key)
);

CREATE TABLE outbox_events (
    id              BIGSERIAL PRIMARY KEY,
    aggregate_type  VARCHAR(50)  NOT NULL,
    aggregate_id    VARCHAR(50)  NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    payload         JSONB        NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at    TIMESTAMPTZ
);
CREATE INDEX idx_outbox_pending ON outbox_events (status, next_attempt_at);

CREATE TABLE processed_webhook_events (
    event_id     VARCHAR(255) PRIMARY KEY,
    event_type   VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
