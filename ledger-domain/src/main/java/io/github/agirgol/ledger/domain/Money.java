package io.github.agirgol.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An amount of money: a magnitude and the currency it is denominated in.
 *
 * <p>Three decisions define this type.
 *
 * <p><b>Never {@code double}.</b> Binary floating point cannot represent 0.10,
 * so {@code 0.1 + 0.2 != 0.3}. In a ledger those fractions of a cent do not
 * cancel out — they accumulate, and a trial balance that is off by 0.0000001
 * is off. Every amount here is a {@link BigDecimal}.
 *
 * <p><b>Scale is normalised to the currency.</b> This is not cosmetic.
 * {@code BigDecimal.equals} compares scale as well as value, so
 * {@code 1.0} and {@code 1.00} are unequal — and since this is a record, that
 * behaviour would leak straight into {@code equals} and {@code hashCode}. Two
 * amounts of one lira would fail to match, hash to different buckets, and break
 * every set and map they landed in. Normalising in the constructor makes the
 * generated {@code equals} correct: 1.0 TRY and 1.00 TRY are the same value,
 * because they are.
 *
 * <p><b>Currencies do not mix.</b> Arithmetic across currencies throws rather
 * than converting. See {@link CurrencyMismatchException}.
 */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

    /**
     * Normalises scale to the currency's own precision.
     *
     * <p>The rounding mode is {@code UNNECESSARY} on purpose: it throws rather
     * than rounding. Constructing {@code Money} with more precision than the
     * currency has — 1.005 TRY, or any amount in JPY with a decimal part — is a
     * mistake at the point it happens, and rounding it away here would hide
     * where the extra precision came from. Callers that genuinely need to round
     * should do it explicitly, where the choice is visible.
     */
    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        amount = amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY);
    }

    public static Money of(BigDecimal amount, Currency currency) {
        return new Money(amount, currency);
    }

    /** Parses from a string, so a literal never passes through {@code double}. */
    public static Money of(String amount, Currency currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money of(String amount, String currencyCode) {
        return of(amount, Currency.getInstance(currencyCode));
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    public Money abs() {
        return new Money(amount.abs(), currency);
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    /**
     * Orders two amounts of the same currency.
     *
     * <p>Consistent with {@code equals} because the constructor has already
     * normalised scale — the usual {@code BigDecimal} trap where
     * {@code compareTo} returns 0 for values {@code equals} calls different
     * cannot arise here.
     */
    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }
}
