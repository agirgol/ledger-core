package io.github.agirgol.ledger.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A balanced, immutable set of entries posted as one unit.
 *
 * <p>The invariant is enforced in the constructor: within each currency, debits
 * equal credits. Not validated on save, not checked by a service — a
 * {@code Transaction} that does not balance cannot be brought into existence.
 * Every later stage can therefore assume the books are square without
 * re-checking, and there is no window in which they are not.
 *
 * <p><b>Each currency balances on its own.</b> A transaction whose lira debits
 * are offset by dollar credits nets to zero only if you assume an exchange
 * rate, and a ledger that assumes one has invented a number. A transaction may
 * span currencies, but each of them has to square by itself; converting between
 * them is a posting the caller makes, not something this class does for them.
 *
 * <p><b>Nothing is ever deleted.</b> Corrections are {@link #reverse}
 * transactions that post the opposite entries. The original stays, which is
 * what makes the history auditable rather than merely current.
 */
public record Transaction(
        TransactionId id,
        Instant occurredAt,
        String description,
        List<Entry> entries) {

    public Transaction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(entries, "entries");

        entries = List.copyOf(entries);

        if (entries.size() < 2) {
            throw new IllegalArgumentException(
                    "A transaction needs at least two entries; a single-sided posting is not double-entry.");
        }

        Map<Currency, Money> imbalance = netByCurrency(entries);
        imbalance.values().removeIf(Money::isZero);
        if (!imbalance.isEmpty()) {
            throw new UnbalancedTransactionException(imbalance);
        }
    }

    public static Transaction of(
            TransactionId id, Instant occurredAt, String description, List<Entry> entries) {
        return new Transaction(id, occurredAt, description, entries);
    }

    public static Transaction of(
            TransactionId id, Instant occurredAt, String description, Entry... entries) {
        return new Transaction(id, occurredAt, description, List.of(entries));
    }

    /** Total debited in one currency. */
    public Money debitTotal(Currency currency) {
        return total(currency, Side.DEBIT);
    }

    /** Total credited in one currency. */
    public Money creditTotal(Currency currency) {
        return total(currency, Side.CREDIT);
    }

    /** Every currency this transaction touches. */
    public Set<Currency> currencies() {
        Set<Currency> currencies = new java.util.LinkedHashSet<>();
        for (Entry entry : entries) {
            currencies.add(entry.amount().currency());
        }
        return Set.copyOf(currencies);
    }

    /** The entries touching one account. */
    public List<Entry> entriesFor(AccountId account) {
        List<Entry> matching = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.account().equals(account)) {
                matching.add(entry);
            }
        }
        return List.copyOf(matching);
    }

    /**
     * A transaction that undoes this one.
     *
     * <p>The reversal is itself a transaction — it balances by construction,
     * because reversing every entry preserves the sums. Nothing is removed;
     * both postings stand in the history, and the net effect is zero.
     */
    public Transaction reverse(TransactionId reversalId, Instant occurredAt) {
        Objects.requireNonNull(reversalId, "reversalId");
        List<Entry> reversed = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            reversed.add(entry.reversed());
        }
        return new Transaction(reversalId, occurredAt, "Reversal of " + id, reversed);
    }

    private Money total(Currency currency, Side side) {
        Money sum = Money.zero(currency);
        for (Entry entry : entries) {
            if (entry.side() == side && entry.amount().currency().equals(currency)) {
                sum = sum.plus(entry.amount());
            }
        }
        return sum;
    }

    /** Debits minus credits, per currency. Zero everywhere is what "balanced" means. */
    private static Map<Currency, Money> netByCurrency(List<Entry> entries) {
        Map<Currency, Money> net = new LinkedHashMap<>();
        for (Entry entry : entries) {
            Currency currency = entry.amount().currency();
            Money signed = entry.isDebit() ? entry.amount() : entry.amount().negate();
            net.merge(currency, signed, Money::plus);
        }
        return net;
    }
}
