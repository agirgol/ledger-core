package io.github.agirgol.ledger.api;

import io.github.agirgol.ledger.domain.Transaction;
import io.github.agirgol.ledger.domain.TransactionId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/**
 * A transaction, in both directions.
 *
 * <p>{@code id} is optional on the way in — supply one to choose it, omit it
 * and the server generates it. It is always present on the way out, because a
 * caller that cannot name what it just posted cannot reverse it later.
 */
public record TransactionView(
        String id,
        @NotNull Instant occurredAt,
        String description,
        @NotEmpty @Valid List<EntryView> entries) {

    public static TransactionView of(Transaction transaction) {
        return new TransactionView(
                transaction.id().value(),
                transaction.occurredAt(),
                transaction.description(),
                transaction.entries().stream().map(EntryView::of).toList());
    }

    public Transaction toDomain() {
        return Transaction.of(
                id == null || id.isBlank() ? TransactionId.random() : TransactionId.of(id),
                occurredAt,
                description == null ? "" : description,
                entries.stream().map(EntryView::toDomain).toList());
    }
}
