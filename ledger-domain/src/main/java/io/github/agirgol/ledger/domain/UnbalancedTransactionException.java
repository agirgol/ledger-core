package io.github.agirgol.ledger.domain;

import java.io.Serial;
import java.util.Currency;
import java.util.Map;

/**
 * Thrown when a transaction's debits and credits do not agree.
 *
 * <p>This is the invariant the whole library exists to hold. It is checked in
 * the constructor rather than by a validator someone has to remember to call,
 * so an unbalanced {@code Transaction} cannot be constructed — not stored,
 * not passed around, not half-committed. There is no state in which the books
 * are wrong and something is planning to fix it later.
 */
public final class UnbalancedTransactionException extends IllegalArgumentException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient Map<Currency, Money> imbalance;

    UnbalancedTransactionException(Map<Currency, Money> imbalance) {
        super(describe(imbalance));
        this.imbalance = Map.copyOf(imbalance);
    }

    /** The net difference per currency: positive means debits exceed credits. */
    public Map<Currency, Money> imbalance() {
        return imbalance;
    }

    private static String describe(Map<Currency, Money> imbalance) {
        StringBuilder message = new StringBuilder("Debits and credits do not balance. ");
        imbalance.forEach((currency, net) -> message
                .append(currency.getCurrencyCode())
                .append(": debits exceed credits by ")
                .append(net)
                .append(net.isNegative() ? " (i.e. credits exceed debits)" : "")
                .append(". "));
        return message.append(
                "Every currency in a transaction must balance on its own; a transaction that nets to zero "
                        + "across currencies is still two unbalanced books.").toString();
    }
}
