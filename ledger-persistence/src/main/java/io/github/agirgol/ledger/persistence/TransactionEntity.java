package io.github.agirgol.ledger.persistence;

import io.github.agirgol.ledger.domain.Entry;
import io.github.agirgol.ledger.domain.Transaction;
import io.github.agirgol.ledger.domain.TransactionId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The stored form of a {@link Transaction}.
 *
 * <p>Reconstituting one goes through the domain constructor, so a row set that
 * does not balance fails on the way out of the database rather than being handed
 * to a caller. A hand-edited or corrupted table surfaces as an exception at read
 * time instead of as a report that is quietly wrong.
 */
@Entity
@Table(name = "transactions")
class TransactionEntity {

    @Id
    private String id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(nullable = false)
    private String description;

    /** Null when the caller supplied no key; unique when present. */
    @Column(name = "idempotency_key", unique = true, length = 128)
    private String idempotencyKey;

    @Column(name = "recorded_at", nullable = false, insertable = false, updatable = false)
    private Instant recordedAt;

    /**
     * Read side only.
     *
     * <p>Entries are written explicitly by {@link LedgerStore} rather than
     * cascaded from here. Cascading made the order of inserts a property of
     * Hibernate's flush timing rather than of the code, which is a poor trade
     * for saving three lines.
     */
    @OneToMany(mappedBy = "transaction", fetch = FetchType.EAGER)
    private List<EntryEntity> entries = new ArrayList<>();

    protected TransactionEntity() {
        // for JPA
    }

    TransactionEntity(Transaction transaction, String idempotencyKey) {
        this.id = transaction.id().value();
        this.occurredAt = transaction.occurredAt();
        this.description = transaction.description();
        this.idempotencyKey = idempotencyKey;
    }

    Transaction toDomain() {
        List<Entry> domainEntries = new ArrayList<>(entries.size());
        for (EntryEntity entry : entries) {
            domainEntries.add(entry.toDomain());
        }
        // Through the domain constructor: an unbalanced row set fails here.
        return new Transaction(TransactionId.of(id), occurredAt, description, domainEntries);
    }

    String id() {
        return id;
    }

    Instant recordedAt() {
        return recordedAt;
    }
}
