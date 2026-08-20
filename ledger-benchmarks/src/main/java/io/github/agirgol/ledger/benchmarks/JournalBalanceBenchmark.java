package io.github.agirgol.ledger.benchmarks;

import io.github.agirgol.ledger.domain.Account;
import io.github.agirgol.ledger.domain.AccountType;
import io.github.agirgol.ledger.domain.Entry;
import io.github.agirgol.ledger.domain.Journal;
import io.github.agirgol.ledger.domain.Money;
import io.github.agirgol.ledger.domain.Transaction;
import io.github.agirgol.ledger.domain.TransactionId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * What it costs to derive a balance by replaying the journal.
 *
 * <p>{@link Journal#balanceOf} is the readable definition of a balance: walk
 * every transaction, take the entries against this account, add them up. It is
 * also the definition the property tests check the stored ledger against, so it
 * has to stay in the library.
 *
 * <p>The question this measures is not whether it is fast — it is linear, and
 * linear in a quantity that only ever grows. The question is where the line
 * crosses from "fine" into "not on a read path", which is the whole reason
 * {@code LedgerStore} aggregates in SQL instead.
 *
 * <p>Every transaction here touches the measured account, so the parameter is
 * both the journal's length and the number of entries behind the balance. That
 * is the worst case and the honest one: an account that appears in one
 * transaction in a thousand would make the replay look far better than it is
 * for the account you actually care about, which is usually cash.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class JournalBalanceBenchmark {

    @Param({"10000", "100000", "1000000"})
    public int entries;

    private Journal journal;
    private Account cash;
    private Instant midpoint;

    @Setup(Level.Trial)
    public void buildJournal() {
        Currency currency = Currency.getInstance("TRY");
        cash = Account.of("cash", "Cash", AccountType.ASSET, currency);
        Account revenue = Account.of("revenue", "Revenue", AccountType.REVENUE, currency);

        // Built once and shared across every transaction. Money and AccountId
        // are immutable values, so reuse changes nothing about what is measured
        // except that a million-entry journal fits in memory at all.
        Money amount = Money.of(new BigDecimal("10.00"), currency);
        Entry debit = Entry.debit(cash.id(), amount);
        Entry credit = Entry.credit(revenue.id(), amount);
        List<Entry> both = List.of(debit, credit);

        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        List<Transaction> all = new ArrayList<>(entries);
        for (int i = 0; i < entries; i++) {
            all.add(Transaction.of(
                    TransactionId.of("t-" + i), start.plusSeconds(i), "sale", both));
        }

        journal = Journal.of(all);
        midpoint = start.plusSeconds(entries / 2);
    }

    /** The current balance: every transaction is in scope. */
    @Benchmark
    public Money currentBalance() {
        return journal.balanceOf(cash);
    }

    /**
     * The balance as it stood halfway through.
     *
     * <p>Measured separately because the point-in-time variant still walks the
     * whole journal — it filters, it does not seek. Half the entries are skipped
     * before any arithmetic happens, so the gap between this and
     * {@link #currentBalance()} is the cost of the addition rather than of the
     * traversal.
     */
    @Benchmark
    public Money balanceAsOfMidpoint() {
        return journal.balanceOf(cash, midpoint);
    }
}
