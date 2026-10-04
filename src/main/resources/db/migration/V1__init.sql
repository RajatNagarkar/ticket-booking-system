CREATE TABLE shows (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name           TEXT        NOT NULL,
    price_paise    BIGINT      NOT NULL CHECK (price_paise >= 0),
    per_user_limit INT         NOT NULL DEFAULT 4 CHECK (per_user_limit > 0),
    total_seats    INT         NOT NULL CHECK (total_seats > 0),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Idempotency: UNIQUE (user_id, idempotency_key) makes a concurrent retry block
-- on the index until the first insert commits, then conflict. request_hash is a
-- hash of the show id and sorted seats, used to reject same-key-different-body.
CREATE TABLE reservations (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    show_id         UUID        NOT NULL REFERENCES shows (id),
    user_id         TEXT        NOT NULL,
    seats           TEXT[]      NOT NULL CHECK (cardinality(seats) > 0),
    amount_paise    BIGINT      NOT NULL CHECK (amount_paise >= 0),
    status          TEXT        NOT NULL CHECK (status IN ('confirmed', 'cancelled')),
    idempotency_key TEXT        NOT NULL,
    request_hash    TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    cancelled_at    TIMESTAMPTZ,
    CONSTRAINT uq_reservations_user_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT ck_reservations_cancelled_at CHECK ((status = 'cancelled') = (cancelled_at IS NOT NULL))
);

-- The atomic decision: UPDATE ... WHERE status = 'available' on rows locked in
-- seat_no order. An available seat must carry no owner; a taken seat must carry
-- both, so a seat can never be half-assigned.
CREATE TABLE seats (
    show_id        UUID NOT NULL REFERENCES shows (id),
    seat_no        TEXT NOT NULL CHECK (length(seat_no) > 0),
    status         TEXT NOT NULL DEFAULT 'available' CHECK (status IN ('available', 'held', 'confirmed')),
    reservation_id UUID REFERENCES reservations (id),
    user_id        TEXT,
    PRIMARY KEY (show_id, seat_no),
    CONSTRAINT ck_seats_owner CHECK (
        (status = 'available') = (reservation_id IS NULL)
        AND (reservation_id IS NULL) = (user_id IS NULL)
    )
);

-- Cancel looks seats up by reservation.
CREATE INDEX idx_seats_reservation ON seats (reservation_id) WHERE reservation_id IS NOT NULL;

-- Per-user limit: seats a user currently holds or owns for a show. Guarded by
-- UPDATE ... SET held = held + n WHERE held + n <= per_user_limit on the locked row.
CREATE TABLE user_show_quota (
    show_id UUID NOT NULL REFERENCES shows (id),
    user_id TEXT NOT NULL,
    held    INT  NOT NULL DEFAULT 0 CHECK (held >= 0),
    PRIMARY KEY (show_id, user_id)
);
