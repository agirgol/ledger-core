package io.github.agirgol.ledger.domain;

import java.util.Objects;
import java.util.UUID;

/** Identifies a transaction. Typed for the same reason as {@link AccountId}. */
public record TransactionId(String value) {

    public TransactionId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("A transaction id cannot be blank.");
        }
    }

    public static TransactionId of(String value) {
        return new TransactionId(value);
    }

    public static TransactionId random() {
        return new TransactionId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
