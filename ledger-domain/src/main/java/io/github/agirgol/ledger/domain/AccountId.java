package io.github.agirgol.ledger.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifies an account.
 *
 * <p>A wrapper rather than a bare {@code UUID} or {@code String}: in a ledger
 * almost everything is an identifier, and untyped ones are interchangeable at
 * the call site. {@code post(accountId, transactionId)} and
 * {@code post(transactionId, accountId)} both compile when both are strings,
 * and the resulting bug surfaces as a balance that is wrong rather than an
 * error that is loud.
 */
public record AccountId(String value) {

    public AccountId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("An account id cannot be blank.");
        }
    }

    public static AccountId of(String value) {
        return new AccountId(value);
    }

    public static AccountId random() {
        return new AccountId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
