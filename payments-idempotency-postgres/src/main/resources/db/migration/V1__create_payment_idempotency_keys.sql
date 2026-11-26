-- Idempotency keys for charges. One row per client-supplied key.
--
-- IN_PROGRESS rows are leases: exactly one worker may hold an unexpired lease on a key. COMPLETED rows hold the final
-- ChargeResult (flat jsonb written by ChargeResultCodec) so retries replay it instead of charging again.
-- Flyway/Liquibase-compatible file name; apply it with your migration tool or PostgresIdempotencySchema.create().
CREATE TABLE IF NOT EXISTS payment_idempotency_keys (
    idempotency_key  text        PRIMARY KEY CHECK (length(idempotency_key) BETWEEN 1 AND 255),
    fingerprint      text        NOT NULL,
    state            text        NOT NULL CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    lease_token      text,
    lease_expires_at timestamptz,
    attempts         integer     NOT NULL CHECK (attempts >= 1),
    result           jsonb,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    CONSTRAINT payment_idempotency_keys_state_shape CHECK (
        (state = 'IN_PROGRESS' AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL AND result IS NULL)
        OR (state = 'COMPLETED' AND lease_token IS NULL AND lease_expires_at IS NULL AND result IS NOT NULL)
    )
);

-- Retention sweeps delete old COMPLETED rows; keep that scan off the primary key.
CREATE INDEX IF NOT EXISTS payment_idempotency_keys_completed_updated_at
    ON payment_idempotency_keys (updated_at)
    WHERE state = 'COMPLETED';
