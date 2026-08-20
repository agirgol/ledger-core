package io.github.agirgol.ledger.domain;

import java.io.Serial;
import java.util.Currency;

/**
 * Thrown when two amounts in different currencies are combined.
 *
 * <p>Java's type system cannot express "a {@code Money} in TRY" as a distinct
 * type from "a {@code Money} in USD" without generic phantom types, which cost
 * more in ceremony than they return. So the check happens at runtime — but it
 * happens on every arithmetic operation, and it throws rather than converting.
 *
 * <p>Converting would require a rate, a rate has a date and a source, and a
 * ledger that silently picks one has invented a number. FX belongs in an
 * explicit conversion entry that records which rate was used, not in an
 * addition operator.
 */
public final class CurrencyMismatchException extends IllegalArgumentException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient Currency left;
    private final transient Currency right;

    CurrencyMismatchException(Currency left, Currency right) {
        super("Cannot combine %s and %s: amounts in different currencies are not commensurable. "
                .formatted(left.getCurrencyCode(), right.getCurrencyCode())
                + "Record an explicit conversion entry with the rate you used instead.");
        this.left = left;
        this.right = right;
    }

    public Currency left() {
        return left;
    }

    public Currency right() {
        return right;
    }
}
