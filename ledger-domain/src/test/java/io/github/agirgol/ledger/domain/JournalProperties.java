package io.github.agirgol.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * The ledger's central guarantee, checked against generated histories.
 *
 * <p>The claim is not "these examples balance". It is that <em>no</em> sequence
 * of transactions can leave the books unbalanced, and that is a statement about
 * all inputs — so it is tested against inputs nobody chose. Each property runs a
 * thousand generated histories per build, and when one fails jqwik shrinks it to
 * the smallest case that still does.
 *
 * <p>Histories are built from transfers rather than arbitrary entry lists.
 * A generator that emitted random debits and credits would spend almost all its
 * time producing unbalanced sets that the constructor rejects, and would test
 * the guard instead of the arithmetic behind it.
 */
class JournalProperties {

    private static final List<Currency> CURRENCIES = List.of(
            Currency.getInstance("TRY"),
            Currency.getInstance("USD"),
            Currency.getInstance("JPY"));

    private static final List<String> CODES =
            List.of("1000-cash", "1200-receivable", "2000-payable", "4000-revenue", "5000-expense");

    /**
     * One account per code per currency, as a real chart of accounts is laid
     * out. Reusing a single id across currencies would generate books that no
     * bookkeeper would keep, and would test the guard against that rather than
     * the arithmetic.
     */
    private static List<AccountId> accountsIn(Currency currency) {
        List<AccountId> ids = new ArrayList<>(CODES.size());
        for (String code : CODES) {
            ids.add(AccountId.of(code + "-" + currency.getCurrencyCode()));
        }
        return ids;
    }

    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

    // ---- the headline ----------------------------------------------------

    @Property
    void the_books_balance_after_any_history(@ForAll("histories") List<Transaction> history) {
        Journal journal = post(history);

        for (Currency currency : CURRENCIES) {
            assertThat(journal.isBalanced(currency))
                    .as("debits equal credits in %s after %d transactions", currency, history.size())
                    .isTrue();
        }
    }

    @Property
    void every_account_movement_nets_to_zero(@ForAll("histories") List<Transaction> history) {
        // The accounting equation, stated without reference to account types:
        // summed across every account, debits minus credits is zero. If this
        // ever held false, some value would have entered or left the system
        // without a counterparty.
        Map<Currency, BigDecimal> net = new HashMap<>();

        for (Transaction transaction : history) {
            for (Entry entry : transaction.entries()) {
                Currency currency = entry.amount().currency();
                BigDecimal signed = entry.isDebit()
                        ? entry.amount().amount()
                        : entry.amount().amount().negate();
                net.merge(currency, signed, BigDecimal::add);
            }
        }

        assertThat(net.values()).allSatisfy(value -> assertThat(value.signum()).isZero());
    }

    // ---- order and time --------------------------------------------------

    @Property
    void posting_order_does_not_change_the_totals(@ForAll("histories") List<Transaction> history) {
        // Balances are sums, and addition is associative and commutative — a
        // property proved separately in MoneyProperties. This checks that the
        // journal actually inherits it rather than introducing an order
        // dependency of its own.
        List<Transaction> shuffled = new ArrayList<>(history);
        java.util.Collections.reverse(shuffled);

        Journal forwards = post(history);
        Journal backwards = post(shuffled);

        for (Currency currency : CURRENCIES) {
            assertThat(forwards.totalDebits(currency)).isEqualTo(backwards.totalDebits(currency));
            assertThat(forwards.totalCredits(currency)).isEqualTo(backwards.totalCredits(currency));
        }
    }

    @Property
    void a_reversal_returns_every_balance_to_where_it_was(
            @ForAll("histories") List<Transaction> history,
            @ForAll("transactions") Transaction extra) {

        Journal before = post(history);
        Journal after = before
                .post(extra)
                .post(extra.reverse(TransactionId.random(), EPOCH.plusSeconds(1_000_000)));

        for (Currency currency : CURRENCIES) {
            for (AccountId id : accountsIn(currency)) {
                Account account = new Account(id, id.value(), AccountType.ASSET, currency);
                assertThat(after.balanceOf(account))
                        .as("account %s in %s after post-and-reverse", id, currency)
                        .isEqualTo(before.balanceOf(account));
            }
        }
    }

    @Property
    void a_point_in_time_balance_ignores_what_had_not_happened_yet(
            @ForAll("histories") List<Transaction> history) {

        Journal journal = post(history);
        Currency currency = CURRENCIES.getFirst();
        Account account = new Account(accountsIn(currency).getFirst(), "cash", AccountType.ASSET, currency);

        // Before the first transaction, nothing has been posted.
        assertThat(journal.balanceOf(account, EPOCH.minusSeconds(1)).isZero()).isTrue();

        // And asking "as of now" is the same as asking for the current balance.
        assertThat(journal.balanceOf(account, Instant.MAX)).isEqualTo(journal.balanceOf(account));
    }

    // ---- generators ------------------------------------------------------

    private static Journal post(List<Transaction> history) {
        Journal journal = Journal.empty();
        for (Transaction transaction : history) {
            journal = journal.post(transaction);
        }
        return journal;
    }

    @Provide
    Arbitrary<List<Transaction>> histories() {
        return transactions().list().ofMinSize(0).ofMaxSize(12);
    }

    @Provide
    Arbitrary<Transaction> transactions() {
        return Arbitraries.of(CURRENCIES).flatMap(currency ->
                transfers(currency).list().ofMinSize(1).ofMaxSize(3)
                        .flatMap(legs -> Arbitraries.longs().between(0, 86_400)
                                .map(offset -> {
                                    List<Entry> entries = new ArrayList<>();
                                    for (List<Entry> pair : legs) {
                                        entries.addAll(pair);
                                    }
                                    return Transaction.of(
                                            TransactionId.random(),
                                            EPOCH.plusSeconds(offset),
                                            "generated",
                                            entries);
                                })));
    }

    /**
     * One transfer: a debit and a credit of the same amount, on different
     * accounts. Balanced by construction, so a transaction assembled from any
     * number of them is balanced too.
     */
    private static Arbitrary<List<Entry>> transfers(Currency currency) {
        // The smallest positive amount is one minor unit, and JPY has none —
        // asking for 0.01 there is the same mistake Money refuses at construction.
        int scale = currency.getDefaultFractionDigits();
        BigDecimal smallest = BigDecimal.ONE.movePointLeft(scale);

        Arbitrary<BigDecimal> amounts = Arbitraries.bigDecimals()
                .between(smallest, new BigDecimal("100000"))
                .ofScale(scale)
                .filter(amount -> amount.signum() > 0);

        List<AccountId> accounts = accountsIn(currency);

        return Combinators.combine(
                        Arbitraries.of(accounts),
                        Arbitraries.of(accounts),
                        amounts)
                .as((from, to, amount) -> {
                    Money money = Money.of(amount, currency);
                    return List.of(Entry.debit(to, money), Entry.credit(from, money));
                });
    }
}
