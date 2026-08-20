-- The ledger schema.
--
-- Two properties are enforced here rather than only in Java, because a database
-- outlives the application that created it: some later service, migration
-- script or console session will reach these tables without going through the
-- domain model, and the guarantees have to survive that.

CREATE TABLE accounts (
    id          VARCHAR(64)  PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    type        VARCHAR(16)  NOT NULL,
    currency    VARCHAR(3)   NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT accounts_type_known
        CHECK (type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    CONSTRAINT accounts_name_present
        CHECK (length(trim(name)) > 0),

    -- ISO 4217 codes are three letters. A constraint rather than CHAR(3),
    -- which pads with spaces and makes 'TRY' and 'TRY ' compare equal in some
    -- contexts and not others.
    CONSTRAINT accounts_currency_iso
        CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE TABLE transactions (
    id              VARCHAR(64)  PRIMARY KEY,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    description     TEXT         NOT NULL,

    -- When a caller retries a request it must not post the same transaction
    -- twice. The uniqueness is declared here rather than checked in the
    -- application, because two concurrent retries would both pass a read-then-
    -- write check and only the database can settle the race.
    idempotency_key VARCHAR(128) UNIQUE,

    -- When the row was written, as distinct from when the event happened.
    -- A correction posted in March for a January transaction has a January
    -- occurred_at and a March recorded_at; point-in-time reporting needs both.
    recorded_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE entries (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id VARCHAR(64)  NOT NULL REFERENCES transactions (id),
    account_id     VARCHAR(64)  NOT NULL REFERENCES accounts (id),
    side           VARCHAR(6)   NOT NULL,

    -- NUMERIC, never float or double precision. Binary floating point cannot
    -- represent 0.10, and in a ledger those fractions accumulate rather than
    -- cancel. The scale is generous: JPY needs 0 digits, KWD needs 3, and a
    -- rate applied during conversion can need more.
    amount         NUMERIC(38, 9) NOT NULL,
    currency       VARCHAR(3)   NOT NULL,

    CONSTRAINT entries_side_known      CHECK (side IN ('DEBIT', 'CREDIT')),

    -- Direction lives in `side`; the amount is a magnitude. A negative amount
    -- would make a credit and a negative debit indistinguishable, and those are
    -- different facts.
    CONSTRAINT entries_amount_positive CHECK (amount > 0),
    CONSTRAINT entries_currency_iso    CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX entries_by_account ON entries (account_id);
CREATE INDEX entries_by_transaction ON entries (transaction_id);
CREATE INDEX transactions_by_time ON transactions (occurred_at);

-- Append-only, enforced by the database.
--
-- The library never updates or deletes an entry — a correction is a reversing
-- transaction. But "never" in application code means "never on this code path",
-- and an audit trail that a stray UPDATE can rewrite is not an audit trail.
CREATE FUNCTION reject_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION
        '% on % is not permitted: the ledger is append-only. Post a reversing transaction instead.',
        TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER entries_are_immutable
    BEFORE UPDATE OR DELETE ON entries
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TRIGGER transactions_are_immutable
    BEFORE UPDATE OR DELETE ON transactions
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
