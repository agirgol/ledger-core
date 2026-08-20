package io.github.agirgol.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MoneyTest {

    private static final Currency TRY = Currency.getInstance("TRY");
    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency JPY = Currency.getInstance("JPY");

    @Test
    @DisplayName("one lira equals one lira, however it was written")
    void scale_does_not_affect_equality() {
        // The trap this type exists to close. BigDecimal.equals compares scale,
        // so 1.0 and 1.00 are unequal — and a record's generated equals would
        // inherit that, quietly breaking every Set and Map holding Money.
        assertThat(Money.of("1.0", TRY)).isEqualTo(Money.of("1.00", TRY));
        assertThat(Money.of("1", TRY)).isEqualTo(Money.of("1.000", TRY));
    }

    @Test
    void equal_amounts_hash_alike() {
        Money a = Money.of("42.5", TRY);
        Money b = Money.of("42.50", TRY);

        assertThat(a).hasSameHashCodeAs(b);
        // A HashSet rather than Set.of: the immutable factory rejects duplicates
        // outright, which would pass for the wrong reason. This asserts that the
        // set collapses them, which is what a caller actually relies on.
        assertThat(new HashSet<>(List.of(a, b))).hasSize(1);
        assertThat(Map.of(a, "x")).containsKey(b);
    }

    @Test
    @DisplayName("a currency's own precision decides the scale")
    void scale_follows_the_currency() {
        assertThat(Money.of("100", JPY).amount().scale()).isZero();
        assertThat(Money.of("100", TRY).amount().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("more precision than the currency has is refused, not rounded away")
    void excess_precision_throws() {
        // Rounding here would hide where the extra digit came from. A caller who
        // genuinely needs to round should do it where the choice is visible.
        assertThatExceptionOfType(ArithmeticException.class)
                .isThrownBy(() -> Money.of("100.5", JPY));
        assertThatExceptionOfType(ArithmeticException.class)
                .isThrownBy(() -> Money.of("1.005", TRY));
    }

    @Test
    void arithmetic_stays_in_one_currency() {
        assertThat(Money.of("10.00", TRY).plus(Money.of("5.50", TRY)))
                .isEqualTo(Money.of("15.50", TRY));
        assertThat(Money.of("10.00", TRY).minus(Money.of("15.50", TRY)))
                .isEqualTo(Money.of("-5.50", TRY));
        assertThat(Money.of("10.00", TRY).negate()).isEqualTo(Money.of("-10.00", TRY));
        assertThat(Money.of("-10.00", TRY).abs()).isEqualTo(Money.of("10.00", TRY));
    }

    @Test
    @DisplayName("combining currencies throws rather than converting")
    void currencies_do_not_mix() {
        Money lira = Money.of("10.00", TRY);
        Money dollars = Money.of("10.00", USD);

        // Converting would need a rate, and a rate has a date and a source. A
        // ledger that picks one silently has invented a number.
        assertThatThrownBy(() -> lira.plus(dollars))
                .isInstanceOf(CurrencyMismatchException.class)
                .hasMessageContaining("TRY")
                .hasMessageContaining("USD");

        assertThatThrownBy(() -> lira.minus(dollars))
                .isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> lira.compareTo(dollars))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void sign_predicates_agree_with_the_amount() {
        assertThat(Money.zero(TRY).isZero()).isTrue();
        assertThat(Money.of("0.00", TRY).isZero()).isTrue();
        assertThat(Money.of("0.01", TRY).isPositive()).isTrue();
        assertThat(Money.of("-0.01", TRY).isNegative()).isTrue();
    }

    @Test
    @DisplayName("compareTo agrees with equals")
    void ordering_is_consistent_with_equality() {
        // The usual BigDecimal hazard — compareTo returning 0 for values equals
        // calls different — cannot arise, because scale is already normalised.
        Money a = Money.of("1.5", TRY);
        Money b = Money.of("1.50", TRY);

        assertThat(a.compareTo(b)).isZero();
        assertThat(a).isEqualTo(b);
        assertThat(Money.of("1.00", TRY)).isLessThan(Money.of("2.00", TRY));
    }

    @Test
    void nulls_are_rejected_at_construction() {
        assertThatThrownBy(() -> new Money(null, TRY)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Money(BigDecimal.ONE, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void reads_as_an_amount_and_a_currency() {
        assertThat(Money.of("1234.5", TRY)).hasToString("1234.50 TRY");
    }
}
