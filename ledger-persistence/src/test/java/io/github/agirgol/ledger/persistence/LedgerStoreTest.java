package io.github.agirgol.ledger.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.agirgol.ledger.domain.Account;
import io.github.agirgol.ledger.domain.AccountId;
import io.github.agirgol.ledger.domain.AccountType;
import io.github.agirgol.ledger.domain.Entry;
import io.github.agirgol.ledger.domain.Money;
import io.github.agirgol.ledger.domain.Transaction;
import io.github.agirgol.ledger.domain.TransactionId;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The stored ledger, against a real Postgres.
 *
 * <p>Testcontainers rather than H2 on purpose. The guarantees under test are
 * Postgres guarantees — NUMERIC arithmetic, a unique constraint settling a race,
 * triggers refusing an UPDATE — and an in-memory database with different
 * semantics would let all three pass while proving nothing about production.
 *
 * <p>Each test opens its own pair of accounts rather than sharing a fixture and
 * cleaning up afterwards. It cannot clean up: the tables reject DELETE, which is
 * the property under test. Isolating by identity instead of by teardown is also
 * closer to how the thing is actually used — a ledger accumulates.
 */
@SpringBootTest
@Testcontainers
class LedgerStoreTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final Currency TRY = Currency.getInstance("TRY");
    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Autowired
    private LedgerStore ledger;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void a_posted_transaction_reads_back_intact() {
        Books books = openBooks();
        TransactionId id = books.post("100.00");

        assertThat(ledger.findTransaction(id))
                .isPresent()
                .get()
                .satisfies(found -> {
                    assertThat(found.entries()).hasSize(2);
                    assertThat(found.debitTotal(TRY)).isEqualTo(Money.of("100.00", TRY));
                });
    }

    @Test
    @DisplayName("the same idempotency key posts once, however many times it is called")
    void idempotent_posting() {
        Books books = openBooks();
        String key = "order-" + UUID.randomUUID();

        Transaction first = books.sale(TransactionId.random(), "100.00", NOW);
        Transaction retry = books.sale(TransactionId.random(), "100.00", NOW);

        Transaction posted = ledger.post(first, key);
        Transaction again = ledger.post(retry, key);

        // The retry returns what was already stored — note the id is the first
        // one's, not the retry's — and the balance moved once.
        assertThat(again.id()).isEqualTo(posted.id()).isEqualTo(first.id());
        assertThat(ledger.balanceOf(books.cash())).isEqualTo(Money.of("100.00", TRY));
    }

    @Test
    @DisplayName("the database aggregate agrees with replaying every entry")
    void sql_balance_matches_the_replayed_balance() {
        Books books = openBooks();
        books.post("100.00");
        books.post("250.50");
        books.post("0.01");

        // Journal is the readable definition of a balance; the SQL aggregate is
        // the one that stays fast. They must not drift apart.
        Money aggregated = ledger.balanceOf(books.cash());
        Money replayed = ledger.journal().balanceOf(books.cash());

        assertThat(aggregated).isEqualTo(replayed).isEqualTo(Money.of("350.51", TRY));
    }

    @Test
    @DisplayName("a revenue account grows with credits, an asset with debits")
    void account_type_decides_the_sign() {
        Books books = openBooks();
        books.post("100.00");

        assertThat(ledger.balanceOf(books.cash())).isEqualTo(Money.of("100.00", TRY));
        assertThat(ledger.balanceOf(books.revenue())).isEqualTo(Money.of("100.00", TRY));
    }

    @Test
    void point_in_time_balance_excludes_later_transactions() {
        Books books = openBooks();
        books.post("100.00", NOW);
        books.post("50.00", NOW.plusSeconds(3600));

        assertThat(ledger.balanceOf(books.cash(), NOW)).isEqualTo(Money.of("100.00", TRY));
        assertThat(ledger.balanceOf(books.cash(), NOW.plusSeconds(7200)))
                .isEqualTo(Money.of("150.00", TRY));
    }

    @Test
    @DisplayName("fractions that binary floating point loses survive the round trip")
    void numeric_precision_is_exact() {
        // 0.10 has no exact binary representation. Ten of them stored as double
        // would not add up to 1.00 — here they must.
        Books books = openBooks();
        for (int i = 0; i < 10; i++) {
            books.post("0.10");
        }

        assertThat(ledger.balanceOf(books.cash())).isEqualTo(Money.of("1.00", TRY));
    }

    @Test
    @DisplayName("the database refuses to update or delete an entry")
    void entries_are_immutable_in_the_database() {
        Books books = openBooks();
        books.post("100.00");

        // The library never issues these statements. The trigger exists because
        // "never" in application code means "never on this code path", and an
        // audit trail a stray UPDATE can rewrite is not an audit trail.
        assertThatThrownBy(() -> jdbc.update(
                        "update entries set amount = 1 where account_id = ?", books.cashId()))
                .hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbc.update(
                        "delete from entries where account_id = ?", books.cashId()))
                .hasMessageContaining("append-only");
    }

    @Test
    void the_database_refuses_to_update_a_transaction() {
        Books books = openBooks();
        TransactionId id = books.post("100.00");

        assertThatThrownBy(() -> jdbc.update(
                        "update transactions set description = 'edited' where id = ?", id.value()))
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("a reversal is appended, not deleted")
    void reversal_leaves_both_postings() {
        Books books = openBooks();
        Transaction sale = books.sale(TransactionId.random(), "100.00", NOW);

        ledger.post(sale);
        ledger.post(sale.reverse(TransactionId.random(), NOW.plusSeconds(60)));

        assertThat(ledger.balanceOf(books.cash()).isZero()).isTrue();
        assertThat(jdbc.queryForObject(
                        "select count(*) from entries where account_id = ?",
                        Integer.class,
                        books.cashId()))
                .isEqualTo(2);
    }

    // ---- fixture ---------------------------------------------------------

    private Books openBooks() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Account cash = Account.of("1000-cash-" + suffix, "Cash", AccountType.ASSET, TRY);
        Account revenue = Account.of("4000-revenue-" + suffix, "Revenue", AccountType.REVENUE, TRY);
        ledger.openAccounts(cash, revenue);
        return new Books(cash, revenue);
    }

    /** One test's own pair of accounts, so tests never see each other's postings. */
    private final class Books {

        private final Account cash;
        private final Account revenue;

        private Books(Account cash, Account revenue) {
            this.cash = cash;
            this.revenue = revenue;
        }

        Account cash() {
            return cash;
        }

        Account revenue() {
            return revenue;
        }

        String cashId() {
            return cash.id().value();
        }

        TransactionId post(String amount) {
            return post(amount, NOW);
        }

        TransactionId post(String amount, Instant at) {
            Transaction transaction = sale(TransactionId.random(), amount, at);
            ledger.post(transaction);
            return transaction.id();
        }

        Transaction sale(TransactionId id, String amount, Instant at) {
            Money money = Money.of(amount, TRY);
            return Transaction.of(
                    id,
                    at,
                    "sale",
                    Entry.debit(AccountId.of(cash.id().value()), money),
                    Entry.credit(AccountId.of(revenue.id().value()), money));
        }
    }
}
