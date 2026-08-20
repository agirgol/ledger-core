package io.github.agirgol.ledger.persistence;

import io.github.agirgol.ledger.domain.Account;
import io.github.agirgol.ledger.domain.AccountId;
import io.github.agirgol.ledger.domain.AccountType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.Currency;

/**
 * The stored form of an {@link Account}.
 *
 * <p>A separate class from the domain record, which is the cost of keeping the
 * domain free of JPA. It is a real cost — this file exists only to be mapped —
 * and it buys something specific: the shape of the table cannot quietly become
 * the shape of the model. An {@code @Entity} on the aggregate is how a lazy
 * association or a column type ends up deciding what a valid account is.
 */
@Entity
@Table(name = "accounts")
class AccountEntity {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountType type;

    @Column(nullable = false, length = 3)
    private String currency;

    /**
     * Optimistic lock for account metadata.
     *
     * <p>Deliberately not on entries: those are append-only, so two concurrent
     * postings to the same account insert two rows and never contend. That is a
     * property of double-entry rather than a trick — nothing is updated in
     * place, so there is nothing to lose an update to. The version here guards
     * renames and reclassifications, where two edits genuinely conflict.
     */
    @Version
    private long version;

    protected AccountEntity() {
        // for JPA
    }

    AccountEntity(Account account) {
        this.id = account.id().value();
        this.name = account.name();
        this.type = account.type();
        this.currency = account.currency().getCurrencyCode();
    }

    Account toDomain() {
        return new Account(AccountId.of(id), name, type, Currency.getInstance(currency));
    }

    long version() {
        return version;
    }
}
