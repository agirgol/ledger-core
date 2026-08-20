package io.github.agirgol.ledger.api;

import java.util.Currency;

/** Turns a currency code into a {@link Currency}, or says why it could not. */
final class Currencies {

    private Currencies() {
    }

    /**
     * <p>{@code Currency.getInstance} throws an {@link IllegalArgumentException}
     * whose message is the offending code and nothing else. At an API boundary
     * that reads like a defect rather than a correctable mistake, so the
     * message says what kind of thing was expected.
     */
    static Currency of(String code) {
        try {
            return Currency.getInstance(code);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw new IllegalArgumentException(
                    "'%s' is not an ISO 4217 currency code.".formatted(code));
        }
    }
}
