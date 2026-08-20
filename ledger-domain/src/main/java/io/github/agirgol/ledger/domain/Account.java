package io.github.agirgol.ledger.domain;

import java.util.Currency;
import java.util.Objects;

/**
 * An account in the chart of accounts.
 *
 * <p>An account is denominated in exactly one currency. A multi-currency
 * business holds one account per currency and records conversions as explicit
 * transactions between them — which is what makes the FX rate, and the date it
 * was taken on, part of the record rather than an assumption buried in a
 * balance calculation.
 */
public record Account(AccountId id, String name, AccountType type, Currency currency) {

    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(currency, "currency");
        if (name.isBlank()) {
            throw new IllegalArgumentException("An account name cannot be blank.");
        }
    }

    public static Account of(String id, String name, AccountType type, Currency currency) {
        return new Account(AccountId.of(id), name, type, currency);
    }

    /** The side on which an increase to this account is recorded. */
    public Side normalBalance() {
        return type.normalBalance();
    }

    public Money zero() {
        return Money.zero(currency);
    }
}
