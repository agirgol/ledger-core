package io.github.agirgol.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.Tuple.Tuple2;
import net.jqwik.api.Tuple.Tuple3;

/**
 * Money under addition, checked against generated inputs rather than chosen ones.
 *
 * <p>These are not extra examples. Each one states something that must hold for
 * <em>every</em> pair or triple of amounts, and jqwik spends a thousand cases per
 * run trying to find a counterexample — then shrinks whatever it finds to the
 * smallest input that still fails, which is usually the one that shows why.
 *
 * <p>Taken together they say: money in a fixed currency forms an abelian group
 * under addition. That is the property the ledger's central invariant leans on.
 * If addition were not associative, the order transactions were posted in would
 * change a balance, and no amount of example-based testing would reliably find
 * the case where it did.
 *
 * <p>The currencies deliberately span three precisions — JPY has no minor unit,
 * most have two, KWD has three — because scale normalisation is where this type
 * is most likely to go wrong.
 */
class MoneyProperties {

    private static final List<Currency> CURRENCIES = List.of(
            Currency.getInstance("TRY"),
            Currency.getInstance("USD"),
            Currency.getInstance("JPY"),
            Currency.getInstance("KWD"));

    // ---- properties ------------------------------------------------------

    @Property
    void addition_is_commutative(@ForAll("pairs") Tuple2<Money, Money> pair) {
        assertThat(pair.get1().plus(pair.get2()))
                .isEqualTo(pair.get2().plus(pair.get1()));
    }

    @Property
    void addition_is_associative(@ForAll("triples") Tuple3<Money, Money, Money> t) {
        Money left = t.get1().plus(t.get2()).plus(t.get3());
        Money right = t.get1().plus(t.get2().plus(t.get3()));

        assertThat(left).isEqualTo(right);
    }

    @Property
    void zero_is_the_identity(@ForAll("monies") Money a) {
        assertThat(a.plus(Money.zero(a.currency()))).isEqualTo(a);
        assertThat(Money.zero(a.currency()).plus(a)).isEqualTo(a);
    }

    @Property
    void every_amount_has_an_inverse(@ForAll("monies") Money a) {
        assertThat(a.plus(a.negate()).isZero()).isTrue();
    }

    @Property
    void negation_is_its_own_inverse(@ForAll("monies") Money a) {
        assertThat(a.negate().negate()).isEqualTo(a);
    }

    @Property
    void subtraction_is_addition_of_the_inverse(@ForAll("pairs") Tuple2<Money, Money> pair) {
        assertThat(pair.get1().minus(pair.get2()))
                .isEqualTo(pair.get1().plus(pair.get2().negate()));
    }

    @Property
    void scale_always_matches_the_currency(@ForAll("monies") Money a) {
        assertThat(a.amount().scale()).isEqualTo(a.currency().getDefaultFractionDigits());
    }

    @Property
    void arithmetic_preserves_the_currency(@ForAll("pairs") Tuple2<Money, Money> pair) {
        Currency c = pair.get1().currency();

        assertThat(pair.get1().plus(pair.get2()).currency()).isEqualTo(c);
        assertThat(pair.get1().minus(pair.get2()).currency()).isEqualTo(c);
        assertThat(pair.get1().negate().currency()).isEqualTo(c);
    }

    @Property
    void ordering_agrees_with_equality(@ForAll("pairs") Tuple2<Money, Money> pair) {
        // The BigDecimal hazard this type closes: compareTo returning 0 for
        // values equals calls different. Asserted over generated input rather
        // than the one pair someone thought to write down.
        boolean equal = pair.get1().equals(pair.get2());
        boolean comparesEqual = pair.get1().compareTo(pair.get2()) == 0;

        assertThat(comparesEqual).isEqualTo(equal);
    }

    @Property
    void equal_amounts_hash_alike(@ForAll("pairs") Tuple2<Money, Money> pair) {
        if (pair.get1().equals(pair.get2())) {
            assertThat(pair.get1()).hasSameHashCodeAs(pair.get2());
        }
    }

    @Property
    void different_currencies_never_combine(@ForAll("mixedPairs") Tuple2<Money, Money> pair) {
        assertThatExceptionOfType(CurrencyMismatchException.class)
                .isThrownBy(() -> pair.get1().plus(pair.get2()));
    }

    // ---- generators ------------------------------------------------------

    @Provide
    Arbitrary<Money> monies() {
        return Arbitraries.of(CURRENCIES).flatMap(MoneyProperties::amountsIn);
    }

    @Provide
    Arbitrary<Tuple2<Money, Money>> pairs() {
        return Arbitraries.of(CURRENCIES).flatMap(currency -> {
            Arbitrary<Money> money = amountsIn(currency);
            return Combinators.combine(money, money).as(Tuple::of);
        });
    }

    @Provide
    Arbitrary<Tuple3<Money, Money, Money>> triples() {
        return Arbitraries.of(CURRENCIES).flatMap(currency -> {
            Arbitrary<Money> money = amountsIn(currency);
            return Combinators.combine(money, money, money).as(Tuple::of);
        });
    }

    @Provide
    Arbitrary<Tuple2<Money, Money>> mixedPairs() {
        Arbitrary<Currency> first = Arbitraries.of(CURRENCIES);
        return first.flatMap(a -> Arbitraries.of(CURRENCIES)
                .filter(b -> !b.equals(a))
                .flatMap(b -> Combinators.combine(amountsIn(a), amountsIn(b)).as(Tuple::of)));
    }

    /**
     * Amounts valid for one currency.
     *
     * <p>Generated at exactly the currency's own scale: the constructor refuses
     * excess precision, so a generator that produced three decimals for JPY
     * would be testing the guard rather than the arithmetic.
     */
    private static Arbitrary<Money> amountsIn(Currency currency) {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("-1000000"), new BigDecimal("1000000"))
                .ofScale(currency.getDefaultFractionDigits())
                .map(amount -> Money.of(amount, currency));
    }
}
