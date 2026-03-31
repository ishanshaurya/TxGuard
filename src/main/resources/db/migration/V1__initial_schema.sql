-- V1__initial_schema.sql

CREATE TABLE charges (
    id                    UUID            PRIMARY KEY,
    idempotency_key       VARCHAR(64)     NOT NULL,
    amount                NUMERIC(19, 4)  NOT NULL CHECK (amount > 0),
    currency              VARCHAR(3)      NOT NULL,
    merchant_reference    VARCHAR(255)    NOT NULL,
    payment_method_token  VARCHAR(255)    NOT NULL,
    status                VARCHAR(20)     NOT NULL DEFAULT 'PENDING'
                              CHECK (status IN ('PENDING','PROCESSING','SETTLED','FAILED')),
    failure_reason        TEXT,
    created_at            TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_charges_idempotency_key ON charges (idempotency_key);
CREATE INDEX idx_charges_status ON charges (status);
CREATE INDEX idx_charges_created_at ON charges (created_at DESC);

CREATE TABLE charge_status_history (
    id            BIGSERIAL       PRIMARY KEY,
    charge_id     UUID            NOT NULL REFERENCES charges(id),
    from_status   VARCHAR(20),
    to_status     VARCHAR(20)     NOT NULL,
    reason        TEXT,
    created_at    TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_charge_status_history_charge_id
    ON charge_status_history (charge_id, created_at DESC);

CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_charges_updated_at
    BEFORE UPDATE ON charges
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
