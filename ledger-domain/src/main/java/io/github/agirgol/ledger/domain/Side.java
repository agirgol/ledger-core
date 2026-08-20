package io.github.agirgol.ledger.domain;

/**
 * Which side of the book an entry lands on.
 *
 * <p>Debit and credit are directions, not signs. Whether a debit increases or
 * decreases an account depends on the account's type — see {@link AccountType} —
 * which is exactly why entries record a side rather than a positive or negative
 * amount. Storing signed amounts collapses that distinction and makes it
 * impossible to tell a credit to an asset from a debit to a liability.
 */
public enum Side {
    DEBIT,
    CREDIT;

    public Side opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
