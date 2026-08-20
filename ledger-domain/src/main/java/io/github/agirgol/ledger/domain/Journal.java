package io.github.agirgol.ledger.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * An append-only sequence of transactions, with balances derived from it.
 *
 * <p>Immutable: {@link #post} returns a new journal rather than mutating this
 * one. Nothing is ever removed — a correction is a {@link Transaction#reverse}
 * appended after the fact, so the history shows both what was recorded and what
 * corrected it. That is the difference between a ledger and a table of current
 * values.
 *
 * <p>This class computes; it does not store. A production ledger keeps
 * transactions in a database and derives balances there, with the same rules.
 * Holding them in a list here is what lets the invariants be tested without
 * infrastructure.
 */
public final class Journal {

    private final List<Transaction> transactions;

    private Journal(List<Transaction> transactions) {
        this.transactions = List.copyOf(transactions);
    }

    public static Journal empty() {
        return new Journal(List.of());
    }

    public static Journal of(List<Transaction> transactions) {
        return new Journal(transactions);
    }

    /** Appends a transaction, returning a new journal. */
    public Journal post(Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction");
        List<Transaction> appended = new ArrayList<>(transactions.size() + 1);
        appended.addAll(transactions);
        appended.add(transaction);
        return new Journal(appended);
    }

    public List<Transaction> transactions() {
        return transactions;
    }

    public int size() {
        return transactions.size();
    }

    /**
     * The balance of one account: a magnitude on the account's normal side.
     *
     * <p>Positive means the account holds what its type expects — an asset with
     * a positive balance has value in it, a liability with a positive balance is
     * owed. Negative means the account is on the wrong side of its own nature,
     * which is meaningful (an overdrawn bank account) rather than an error.
     */
    public Money balanceOf(Account account) {
        return balanceOf(account, Instant.MAX);
    }

    /**
     * The balance as it stood at a moment in time.
     *
     * <p>Derived by replaying entries up to {@code asOf} rather than by storing
     * running totals. A stored total is a second source of truth that can drift
     * from the entries; a replayed one cannot, and it is what makes restating a
     * prior period possible after a late correction is posted.
     */
    public Money balanceOf(Account account, Instant asOf) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(asOf, "asOf");

        Money balance = account.zero();
        for (Transaction transaction : transactions) {
            if (transaction.occurredAt().isAfter(asOf)) {
                continue;
            }
            for (Entry entry : transaction.entriesFor(account.id())) {
                requireSameCurrency(account, entry, transaction);
                balance = balance.plus(entry.signedFor(account.type()));
            }
        }
        return balance;
    }

    /** Everything debited in one currency, across every transaction. */
    public Money totalDebits(Currency currency) {
        return total(currency, Side.DEBIT);
    }

    /** Everything credited in one currency, across every transaction. */
    public Money totalCredits(Currency currency) {
        return total(currency, Side.CREDIT);
    }

    /**
     * Whether the books balance in one currency.
     *
     * <p>This should be impossible to violate, since every transaction balances
     * by construction and a sum of balanced things is balanced. It exists so the
     * claim can be asserted rather than assumed — including against thousands of
     * randomly generated histories.
     */
    public boolean isBalanced(Currency currency) {
        return totalDebits(currency).equals(totalCredits(currency));
    }

    /**
     * Refuses to compute a balance from entries in the wrong currency.
     *
     * <p>An account is denominated in one currency; an entry against it in
     * another is a posting error somewhere upstream. The tempting alternative —
     * skipping the mismatched entry — produces a balance that looks fine and is
     * wrong by exactly the amount that was skipped, with nothing on screen to
     * say so. Better to refuse and name both the account and the transaction.
     */
    private static void requireSameCurrency(Account account, Entry entry, Transaction transaction) {
        Currency entryCurrency = entry.amount().currency();
        if (!account.currency().equals(entryCurrency)) {
            throw new IllegalStateException(
                    "Account %s is denominated in %s but transaction %s posts %s against it. "
                            .formatted(
                                    account.id(),
                                    account.currency().getCurrencyCode(),
                                    transaction.id(),
                                    entryCurrency.getCurrencyCode())
                            + "Hold one account per currency and record conversions as explicit transactions "
                            + "between them.");
        }
    }

    private Money total(Currency currency, Side side) {
        Money sum = Money.zero(currency);
        for (Transaction transaction : transactions) {
            for (Entry entry : transaction.entries()) {
                if (entry.side() == side && entry.amount().currency().equals(currency)) {
                    sum = sum.plus(entry.amount());
                }
            }
        }
        return sum;
    }
}
