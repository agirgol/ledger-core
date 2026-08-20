package io.github.agirgol.ledger.benchmarks;

import io.github.agirgol.ledger.domain.Account;
import io.github.agirgol.ledger.domain.AccountId;
import io.github.agirgol.ledger.domain.AccountType;
import io.github.agirgol.ledger.domain.Entry;
import io.github.agirgol.ledger.domain.Journal;
import io.github.agirgol.ledger.domain.Money;
import io.github.agirgol.ledger.domain.Side;
import io.github.agirgol.ledger.domain.Transaction;
import io.github.agirgol.ledger.domain.TransactionId;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.postgresql.PGConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Aggregating a balance in the database against replaying it in memory.
 *
 * <p>This is the measurement the library's design rests on. {@code LedgerStore}
 * carries two ways to answer "what is this account's balance": a SQL aggregate,
 * and {@code journal().balanceOf(...)}, which loads the book and replays it
 * through the domain. A test asserts the two agree. Agreement is not the
 * interesting part — if they disagreed one of them would be a bug. The
 * interesting part is what the second one costs, because that cost is the only
 * reason the first one exists.
 *
 * <p>Against a real Postgres, on the schema the library ships, loaded through
 * the migration rather than a copy of it. An in-memory database would answer
 * faster and tell us nothing: the question here is what a query planner does
 * with an index and a join, and H2's answer is not Postgres's.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 2, time = 5)
@Measurement(iterations = 3, time = 5)
public class StoredBalanceBenchmark {

    private static final Currency TRY = Currency.getInstance("TRY");
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final String CASH = "cash";
    private static final String REVENUE = "revenue";

    /** Rows per COPY chunk. Setup is not measured, but it should still finish. */
    private static final int CHUNK = 50_000;

    @Param({"10000", "100000", "1000000"})
    public int entries;

    private PostgreSQLContainer postgres;
    private Connection connection;
    private Account cash;
    private Instant asOf;

    @Setup(Level.Trial)
    public void loadLedger() throws Exception {
        cash = Account.of(CASH, "Cash", AccountType.ASSET, TRY);
        asOf = START.plusSeconds(entries + 1);

        postgres = new PostgreSQLContainer("postgres:17-alpine");
        postgres.start();

        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

        openAccounts();
        copyTransactions();
        copyEntries();

        // Without this the planner works from the statistics of an empty table
        // and picks a sequential scan on a million rows, which would measure a
        // misconfigured database rather than a design choice.
        try (Statement analyze = connection.createStatement()) {
            analyze.execute("ANALYZE transactions");
            analyze.execute("ANALYZE entries");
        }

        verifyAllThreeAgree();
    }

    /**
     * Refuses to measure until every path returns the same balance.
     *
     * <p>A benchmark that quietly measures the wrong answer is worse than no
     * benchmark: it produces a number, the number goes in a README, and nobody
     * looks again. The comparison is only meaningful if all four are computing
     * the same thing, so that is checked before any of them is timed.
     */
    private void verifyAllThreeAgree() throws Exception {
        // Every transaction debits cash 10.00, and cash is an asset, so the
        // balance is simply the transaction count times ten.
        BigDecimal expected =
                new BigDecimal(entries).multiply(new BigDecimal("10")).setScale(2);

        BigDecimal viaAsOf = aggregateAsOf().setScale(2);
        BigDecimal viaJoin = aggregateAsOfViaJoin().setScale(2);
        BigDecimal viaCurrent = aggregateCurrent().setScale(2);
        BigDecimal viaReplay = loadAndReplay().amount();

        if (viaAsOf.compareTo(expected) != 0
                || viaJoin.compareTo(expected) != 0
                || viaCurrent.compareTo(expected) != 0
                || viaReplay.compareTo(expected) != 0) {
            throw new IllegalStateException(
                    "The balance paths disagree at %d entries: expected %s, "
                            .formatted(entries, expected)
                            + "aggregateAsOf=%s viaJoin=%s aggregateCurrent=%s loadAndReplay=%s"
                                    .formatted(viaAsOf, viaJoin, viaCurrent, viaReplay));
        }
    }

    @TearDown(Level.Trial)
    public void close() throws Exception {
        if (connection != null) {
            connection.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    /**
     * The query {@code LedgerStore.balanceOf} runs: sum in the database, as of
     * a moment, reading the entry's own timestamp.
     */
    @Benchmark
    public BigDecimal aggregateAsOf() throws Exception {
        String sql = """
                SELECT coalesce(sum(CASE WHEN side = 'DEBIT' THEN amount ELSE -amount END), 0)
                FROM entries
                WHERE account_id = ? AND currency = ? AND occurred_at <= ?
                """;
        return sumAsOf(sql);
    }

    /**
     * The same balance, reached by joining back to the transaction.
     *
     * <p>Kept as the control. This is what the query looked like before V2
     * carried {@code occurred_at} onto the entry, and it is the measurement
     * that argued for the change — so it stays here, where the claim in the
     * migration's comment can be re-checked rather than taken on trust.
     */
    @Benchmark
    public BigDecimal aggregateAsOfViaJoin() throws Exception {
        String sql = """
                SELECT coalesce(sum(CASE WHEN e.side = 'DEBIT' THEN e.amount ELSE -e.amount END), 0)
                FROM entries e
                JOIN transactions t ON t.id = e.transaction_id
                WHERE e.account_id = ? AND e.currency = ? AND t.occurred_at <= ?
                """;
        return sumAsOf(sql);
    }

    private BigDecimal sumAsOf(String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, CASH);
            statement.setString(2, "TRY");
            statement.setTimestamp(3, Timestamp.from(asOf));
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getBigDecimal(1);
            }
        }
    }

    /**
     * The same sum without the point-in-time join.
     *
     * <p>A diagnostic rather than a proposal: it isolates what the join to
     * {@code transactions} costs. If the gap is large it is an argument for
     * carrying {@code occurred_at} on the entry as well, and if it is small it
     * is an argument for leaving the schema normalised. Either way the answer
     * should come from a measurement, not from taste.
     */
    @Benchmark
    public BigDecimal aggregateCurrent() throws Exception {
        String sql = """
                SELECT coalesce(sum(CASE WHEN side = 'DEBIT' THEN amount ELSE -amount END), 0)
                FROM entries
                WHERE account_id = ? AND currency = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, CASH);
            statement.setString(2, "TRY");
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getBigDecimal(1);
            }
        }
    }

    /**
     * Load the whole book and replay it through {@link Journal}.
     *
     * <p>What {@code LedgerStore.journal()} does, and what any implementation
     * that keeps the balance definition in the domain has to do if it does not
     * push the sum down into SQL.
     */
    @Benchmark
    public Money loadAndReplay() throws Exception {
        String sql = """
                SELECT t.id, t.occurred_at, e.account_id, e.side, e.amount, e.currency
                FROM transactions t
                JOIN entries e ON e.transaction_id = t.id
                ORDER BY t.occurred_at, t.id
                """;
        List<Transaction> all = new ArrayList<>(entries);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setFetchSize(10_000);
            connection.setAutoCommit(false);
            try (ResultSet rows = statement.executeQuery()) {
                String currentId = null;
                Instant currentAt = null;
                List<Entry> pending = new ArrayList<>(2);
                while (rows.next()) {
                    String id = rows.getString(1);
                    if (currentId != null && !currentId.equals(id)) {
                        all.add(Transaction.of(
                                TransactionId.of(currentId), currentAt, "sale", List.copyOf(pending)));
                        pending.clear();
                    }
                    currentId = id;
                    currentAt = rows.getTimestamp(2).toInstant();
                    Money amount = Money.of(
                            rows.getBigDecimal(5), Currency.getInstance(rows.getString(6)));
                    AccountId account = AccountId.of(rows.getString(3));
                    pending.add(Side.valueOf(rows.getString(4)) == Side.DEBIT
                            ? Entry.debit(account, amount)
                            : Entry.credit(account, amount));
                }
                if (currentId != null) {
                    all.add(Transaction.of(
                            TransactionId.of(currentId), currentAt, "sale", List.copyOf(pending)));
                }
            }
            connection.setAutoCommit(true);
        }
        return Journal.of(all).balanceOf(cash);
    }

    private void openAccounts() throws Exception {
        String sql = "INSERT INTO accounts (id, name, type, currency, version) VALUES (?, ?, ?, ?, 0)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (String[] account : new String[][] {
                {CASH, "Cash", "ASSET"}, {REVENUE, "Revenue", "REVENUE"}
            }) {
                statement.setString(1, account[0]);
                statement.setString(2, account[1]);
                statement.setString(3, account[2]);
                statement.setString(4, "TRY");
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void copyTransactions() throws Exception {
        copyInChunks(
                "COPY transactions (id, occurred_at, description) FROM STDIN WITH (FORMAT csv)",
                (row, out) -> out
                        .append("t-").append(Integer.toString(row))
                        .append(',').append(START.plusSeconds(row).toString())
                        .append(",sale\n"));
    }

    private void copyEntries() throws Exception {
        copyInChunks(
                "COPY entries (transaction_id, account_id, side, amount, currency) "
                        + "FROM STDIN WITH (FORMAT csv)",
                (row, out) -> out
                        .append("t-").append(Integer.toString(row))
                        .append(',').append(CASH).append(",DEBIT,10.00,TRY\n")
                        .append("t-").append(Integer.toString(row))
                        .append(',').append(REVENUE).append(",CREDIT,10.00,TRY\n"));
    }

    /** Streams generated rows through COPY, which loads a million rows in seconds. */
    private void copyInChunks(String sql, RowWriter writer) throws Exception {
        var copy = connection.unwrap(PGConnection.class).getCopyAPI();
        for (int start = 0; start < entries; start += CHUNK) {
            int end = Math.min(start + CHUNK, entries);
            StringBuilder buffer = new StringBuilder(CHUNK * 64);
            for (int row = start; row < end; row++) {
                writer.write(row, buffer);
            }
            copy.copyIn(sql, new StringReader(buffer.toString()));
        }
    }

    @FunctionalInterface
    private interface RowWriter {
        void write(int row, StringBuilder out);
    }
}
