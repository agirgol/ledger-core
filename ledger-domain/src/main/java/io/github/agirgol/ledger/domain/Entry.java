package io.github.agirgol.ledger.domain;

import java.util.Objects;

/**
 * One leg of a transaction: an amount, a side, and the account it touches.
 *
 * <p>The amount is always positive. Direction lives in {@link Side}, not in the
 * sign, because a negative debit and a positive credit are not the same fact
 * even when they move a balance the same way — one is a correction, the other
 * is an ordinary posting, and a ledger that cannot tell them apart cannot
 * produce an audit trail.
 */
public record Entry(AccountId account, Side side, Money amount) {

    public Entry {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(amount, "amount");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException(
                    "An entry amount must be positive; direction is carried by the side, not the sign. "
                            + "To move value the other way, use " + side.opposite() + " instead of a negative "
                            + side + " of " + amount + ".");
        }
    }

    public static Entry debit(AccountId account, Money amount) {
        return new Entry(account, Side.DEBIT, amount);
    }

    public static Entry credit(AccountId account, Money amount) {
        return new Entry(account, Side.CREDIT, amount);
    }

    public boolean isDebit() {
        return side == Side.DEBIT;
    }

    public boolean isCredit() {
        return side == Side.CREDIT;
    }

    /** The same amount on the opposite side — the leg that reverses this one. */
    public Entry reversed() {
        return new Entry(account, side.opposite(), amount);
    }

    /**
     * The amount as a signed contribution to this account's balance.
     *
     * <p>Only meaningful once you know the account's type, which is why this
     * takes it rather than assuming. An expense credited is a reduction; a
     * liability credited is an increase.
     */
    public Money signedFor(AccountType type) {
        return type.increasedBy(side) ? amount : amount.negate();
    }
}
