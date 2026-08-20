-- Carry the transaction's timestamp on the entry.
--
-- A balance as of a moment was answered by joining every entry of an account
-- back to `transactions` for its `occurred_at`. Measured over a million
-- entries, that join cost three times the aggregate itself: 161 ms against the
-- 52 ms the same sum takes without it. See ledger-benchmarks.
--
-- Denormalisation is normally a trade of correctness for speed, because the
-- copy can drift from the original. Here it cannot. Both tables reject UPDATE,
-- so neither value can change after it is written, and the copy is not made by
-- the application at all — a trigger derives it from the parent row on insert.
-- There is no code path that can write a wrong one.

ALTER TABLE entries ADD COLUMN occurred_at TIMESTAMPTZ;

-- The immutability trigger would reject the backfill, so it comes off for the
-- length of this migration and goes straight back on. This is the one moment
-- an entry is permitted to change, and it exists only because the column being
-- filled did not exist when the rows were written.
DROP TRIGGER entries_are_immutable ON entries;

UPDATE entries e
SET occurred_at = t.occurred_at
FROM transactions t
WHERE t.id = e.transaction_id;

ALTER TABLE entries ALTER COLUMN occurred_at SET NOT NULL;

CREATE TRIGGER entries_are_immutable
    BEFORE UPDATE OR DELETE ON entries
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- Derived, not supplied. The application never sets this column; whatever it
-- sends is overwritten from the parent transaction. That is what makes the
-- copy safe to rely on: it is not a second fact, it is the same fact read
-- through a shorter path.
CREATE FUNCTION derive_entry_occurred_at() RETURNS trigger AS $$
BEGIN
    NEW.occurred_at := (SELECT t.occurred_at FROM transactions t WHERE t.id = NEW.transaction_id);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER entries_derive_occurred_at
    BEFORE INSERT ON entries
    FOR EACH ROW EXECUTE FUNCTION derive_entry_occurred_at();

-- Everything the balance query reads, in one index, in the order it filters:
-- the account, then its currency, then the time bound. `side` and `amount` ride
-- along so the sum never has to visit the table.
CREATE INDEX entries_balance ON entries (account_id, currency, occurred_at)
    INCLUDE (side, amount);

-- Redundant now: the new index begins with the same column.
DROP INDEX entries_by_account;
