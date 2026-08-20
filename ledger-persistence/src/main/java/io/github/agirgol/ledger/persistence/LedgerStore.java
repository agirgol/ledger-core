package io.github.agirgol.ledger.persistence;

import io.github.agirgol.ledger.domain.Account;
import io.github.agirgol.ledger.domain.AccountType;
import io.github.agirgol.ledger.domain.Journal;
import io.github.agirgol.ledger.domain.Money;
import io.github.agirgol.ledger.domain.Side;
import io.github.agirgol.ledger.domain.Transaction;
import io.github.agirgol.ledger.domain.TransactionId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The stored ledger: post transactions, read balances.
 *
 * <p>Nothing here re-checks that a transaction balances. It cannot be
 * constructed unbalanced, so by the time one arrives the question is settled —
 * which is the return on enforcing the invariant in a constructor rather than
 * in a service.
 */
@Service
public class LedgerStore {

    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final EntryRepository entries;

    LedgerStore(
            AccountRepository accounts,
            TransactionRepository transactions,
            EntryRepository entries) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.entries = entries;
    }

    /**
     * Opens an account, or returns the existing one.
     *
     * <p>Idempotent because opening is a setup step that gets repeated —
     * a redeploy, a retried migration, a test that runs twice. Failing on the
     * second call would push every caller into a read-then-write dance to
     * avoid it.
     */
    @Transactional
    public Account openAccount(Account account) {
        Objects.requireNonNull(account, "account");
        return accounts
                .findById(account.id().value())
                .map(AccountEntity::toDomain)
                .orElseGet(() -> accounts.save(new AccountEntity(account)).toDomain());
    }

    @Transactional(readOnly = true)
    public Optional<Account> findAccount(String id) {
        return accounts.findById(id).map(AccountEntity::toDomain);
    }

    /** Posts a transaction. Not idempotent — a retry posts it twice. */
    @Transactional
    public Transaction post(Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction");
        write(transaction, null);
        // The argument is returned rather than a re-read of what was written:
        // the transaction was valid before it was stored, and storing does not
        // change it.
        return transaction;
    }

    /**
     * Posts a transaction once per key, however many times it is called.
     *
     * <p>The check is not read-then-write: two concurrent retries would both
     * find nothing and both insert. Instead the insert is attempted and the
     * unique constraint is allowed to settle the race — the loser catches the
     * violation and reads back what the winner wrote. A ledger is exactly the
     * place where "probably not a duplicate" is not good enough.
     */
    @Transactional
    public Transaction post(Transaction transaction, String idempotencyKey) {
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");

        Optional<TransactionEntity> existing = transactions.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get().toDomain();
        }

        try {
            write(transaction, idempotencyKey);
            transactions.flush();
            return transaction;
        } catch (DataIntegrityViolationException race) {
            return transactions
                    .findByIdempotencyKey(idempotencyKey)
                    .map(TransactionEntity::toDomain)
                    .orElseThrow(() -> race);
        }
    }

    /** Writes the header, then its entries. Order matters: entries reference it. */
    private void write(Transaction transaction, String idempotencyKey) {
        TransactionEntity header =
                transactions.save(new TransactionEntity(transaction, idempotencyKey));
        List<EntryEntity> rows = new ArrayList<>(transaction.entries().size());
        for (io.github.agirgol.ledger.domain.Entry entry : transaction.entries()) {
            rows.add(new EntryEntity(header, entry));
        }
        entries.saveAll(rows);
    }

    @Transactional(readOnly = true)
    public Optional<Transaction> findTransaction(TransactionId id) {
        return transactions.findById(id.value()).map(TransactionEntity::toDomain);
    }

    /** The current balance, aggregated in the database. */
    @Transactional(readOnly = true)
    public Money balanceOf(Account account) {
        return balanceOf(account, Instant.now());
    }

    /**
     * The balance as it stood at a moment, aggregated in the database.
     *
     * <p>Equivalent to replaying every entry through {@link Journal}, and a test
     * asserts the two agree. The replay is the readable definition; this is the
     * one that stays fast when the account has a million entries behind it.
     */
    @Transactional(readOnly = true)
    public Money balanceOf(Account account, Instant asOf) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(asOf, "asOf");

        BigDecimal net = entries.netByAccount(
                account.id().value(), account.currency().getCurrencyCode(), asOf);

        BigDecimal signed = account.type().increasedBy(Side.DEBIT) ? net : net.negate();
        return Money.of(
                signed.setScale(account.currency().getDefaultFractionDigits(), RoundingMode.UNNECESSARY),
                account.currency());
    }

    /**
     * The whole ledger as an in-memory journal.
     *
     * <p>Loads everything, so it suits a test, a small book, or a reconciliation
     * run — not a hot path. It exists because the domain's own definition of a
     * balance lives in {@link Journal}, and being able to produce one from
     * stored data is what lets the database aggregate be checked against it.
     */
    @Transactional(readOnly = true)
    public Journal journal() {
        List<Transaction> all = new ArrayList<>();
        for (TransactionEntity entity : transactions.findAllOrdered()) {
            all.add(entity.toDomain());
        }
        return Journal.of(all);
    }

    /** Convenience for opening several accounts at once. */
    @Transactional
    public void openAccounts(Account... toOpen) {
        for (Account account : toOpen) {
            openAccount(account);
        }
    }

    /** The account types this store recognises, for callers building a chart. */
    public static AccountType[] accountTypes() {
        return AccountType.values();
    }
}
