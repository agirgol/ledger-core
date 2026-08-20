package io.github.agirgol.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransactionTest {

    private static final Currency TRY = Currency.getInstance("TRY");
    private static final Currency USD = Currency.getInstance("USD");
    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    private static final AccountId CASH = AccountId.of("1000-cash");
    private static final AccountId REVENUE = AccountId.of("4000-revenue");
    private static final AccountId FX = AccountId.of("1010-cash-usd");

    @Test
    @DisplayName("an unbalanced transaction cannot be constructed at all")
    void imbalance_is_refused_at_construction() {
        // Not validated on save, not checked by a service. There is no moment in
        // which an unbalanced transaction exists and something intends to fix it.
        assertThatExceptionOfType(UnbalancedTransactionException.class)
                .isThrownBy(() -> Transaction.of(
                        TransactionId.random(),
                        NOW,
                        "sale",
                        Entry.debit(CASH, Money.of("100.00", TRY)),
                        Entry.credit(REVENUE, Money.of("90.00", TRY))))
                .withMessageContaining("do not balance")
                .withMessageContaining("TRY");
    }

    @Test
    void a_balanced_transaction_is_accepted() {
        Transaction sale = Transaction.of(
                TransactionId.of("t1"),
                NOW,
                "sale",
                Entry.debit(CASH, Money.of("100.00", TRY)),
                Entry.credit(REVENUE, Money.of("100.00", TRY)));

        assertThat(sale.debitTotal(TRY)).isEqualTo(Money.of("100.00", TRY));
        assertThat(sale.creditTotal(TRY)).isEqualTo(Money.of("100.00", TRY));
        assertThat(sale.currencies()).containsExactly(TRY);
    }

    @Test
    @DisplayName("splits are fine as long as the sides still agree")
    void many_legs_are_allowed() {
        Transaction split = Transaction.of(
                TransactionId.of("t2"),
                NOW,
                "split sale",
                Entry.debit(CASH, Money.of("60.00", TRY)),
                Entry.debit(AccountId.of("1200-receivable"), Money.of("40.00", TRY)),
                Entry.credit(REVENUE, Money.of("100.00", TRY)));

        assertThat(split.entries()).hasSize(3);
        assertThat(split.debitTotal(TRY)).isEqualTo(split.creditTotal(TRY));
    }

    @Test
    @DisplayName("each currency must balance on its own")
    void currencies_balance_separately() {
        // Lira debits offset by dollar credits only net to zero if you assume a
        // rate — and a ledger that assumes one has invented a number.
        assertThatExceptionOfType(UnbalancedTransactionException.class)
                .isThrownBy(() -> Transaction.of(
                        TransactionId.random(),
                        NOW,
                        "cross-currency",
                        Entry.debit(CASH, Money.of("100.00", TRY)),
                        Entry.credit(FX, Money.of("100.00", USD))))
                .withMessageContaining("Every currency in a transaction must balance on its own");
    }

    @Test
    void a_transaction_may_span_currencies_if_each_side_balances() {
        Transaction settlement = Transaction.of(
                TransactionId.of("t3"),
                NOW,
                "two books, both square",
                Entry.debit(CASH, Money.of("100.00", TRY)),
                Entry.credit(AccountId.of("2000-payable"), Money.of("100.00", TRY)),
                Entry.debit(FX, Money.of("10.00", USD)),
                Entry.credit(AccountId.of("2010-payable-usd"), Money.of("10.00", USD)));

        assertThat(settlement.currencies()).containsExactlyInAnyOrder(TRY, USD);
        assertThat(settlement.debitTotal(USD)).isEqualTo(Money.of("10.00", USD));
    }

    @Test
    void a_single_sided_posting_is_not_double_entry() {
        assertThatThrownBy(() -> Transaction.of(
                        TransactionId.random(),
                        NOW,
                        "orphan",
                        List.of(Entry.debit(CASH, Money.of("100.00", TRY)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least two entries");
    }

    @Test
    @DisplayName("entry amounts are positive; direction is the side")
    void negative_amounts_are_refused() {
        assertThatThrownBy(() -> Entry.debit(CASH, Money.of("-5.00", TRY)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CREDIT");
    }

    @Test
    @DisplayName("a reversal undoes without deleting")
    void reversal_leaves_both_postings_standing() {
        Transaction sale = Transaction.of(
                TransactionId.of("t4"),
                NOW,
                "sale",
                Entry.debit(CASH, Money.of("100.00", TRY)),
                Entry.credit(REVENUE, Money.of("100.00", TRY)));

        Transaction reversal = sale.reverse(TransactionId.of("t4r"), NOW.plusSeconds(60));

        assertThat(reversal.entries()).hasSize(2);
        assertThat(reversal.entriesFor(CASH).getFirst().side()).isEqualTo(Side.CREDIT);
        assertThat(reversal.description()).contains("t4");

        Account cash = Account.of("1000-cash", "Cash", AccountType.ASSET, TRY);
        Journal journal = Journal.empty().post(sale).post(reversal);

        assertThat(journal.balanceOf(cash).isZero()).isTrue();
        assertThat(journal.size()).isEqualTo(2);
    }
}
