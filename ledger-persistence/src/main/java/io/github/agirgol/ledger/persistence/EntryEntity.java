package io.github.agirgol.ledger.persistence;

import io.github.agirgol.ledger.domain.AccountId;
import io.github.agirgol.ledger.domain.Entry;
import io.github.agirgol.ledger.domain.Money;
import io.github.agirgol.ledger.domain.Side;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Currency;

/** The stored form of an {@link Entry}. Never updated; see the schema triggers. */
@Entity
@Table(name = "entries")
class EntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private TransactionEntity transaction;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Side side;

    /**
     * NUMERIC in the database, {@link BigDecimal} here, never {@code double}
     * anywhere in between. The scale is restored from the currency on the way
     * out, because Postgres returns the column's scale rather than the one the
     * value was written with.
     */
    @Column(nullable = false, precision = 38, scale = 9)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    protected EntryEntity() {
        // for JPA
    }

    EntryEntity(TransactionEntity transaction, Entry entry) {
        this.transaction = transaction;
        this.accountId = entry.account().value();
        this.side = entry.side();
        this.amount = entry.amount().amount();
        this.currency = entry.amount().currency().getCurrencyCode();
    }

    Entry toDomain() {
        Currency denomination = Currency.getInstance(currency);
        // setScale on the way out: the column holds scale 9 regardless of what
        // was written, and Money requires the currency's own scale.
        BigDecimal restored = amount.setScale(
                denomination.getDefaultFractionDigits(), java.math.RoundingMode.UNNECESSARY);
        return new Entry(AccountId.of(accountId), side, Money.of(restored, denomination));
    }
}
