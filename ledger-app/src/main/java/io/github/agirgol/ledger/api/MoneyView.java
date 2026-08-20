package io.github.agirgol.ledger.api;

import io.github.agirgol.ledger.domain.Money;

/**
 * An amount on the wire.
 *
 * <p>The amount is a string, not a JSON number. JSON itself would carry the
 * decimal exactly, but a client that parses it into a double would not, and a
 * ledger that refuses {@code double} internally has no business handing one out
 * at the edge. A string forces the other side to make the same choice
 * deliberately.
 */
public record MoneyView(String amount, String currency) {

    public static MoneyView of(Money money) {
        return new MoneyView(
                money.amount().toPlainString(), money.currency().getCurrencyCode());
    }
}
