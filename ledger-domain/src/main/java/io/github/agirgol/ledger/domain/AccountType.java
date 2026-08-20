package io.github.agirgol.ledger.domain;

/**
 * The five account classes of double-entry bookkeeping.
 *
 * <p>Each carries a normal balance: the side on which an increase is recorded.
 * Assets and expenses grow with debits; liabilities, equity and revenue grow
 * with credits. This is not a convention that could have gone the other way —
 * it falls out of the accounting equation, {@code assets = liabilities + equity},
 * with revenue and expense as temporary subdivisions of equity.
 */
public enum AccountType {
    ASSET(Side.DEBIT),
    EXPENSE(Side.DEBIT),
    LIABILITY(Side.CREDIT),
    EQUITY(Side.CREDIT),
    REVENUE(Side.CREDIT);

    private final Side normalBalance;

    AccountType(Side normalBalance) {
        this.normalBalance = normalBalance;
    }

    /** The side on which an increase to this kind of account is recorded. */
    public Side normalBalance() {
        return normalBalance;
    }

    /**
     * Whether an entry on this side increases the account.
     *
     * <p>A balance is never negative in the arithmetic sense here; it is a
     * magnitude on one side or the other. This is what lets a caller ask "is
     * this account overdrawn" without knowing whether a minus sign means
     * overdrawn or simply credit-normal.
     */
    public boolean increasedBy(Side side) {
        return side == normalBalance;
    }
}
