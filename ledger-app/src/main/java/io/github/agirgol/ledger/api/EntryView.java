package io.github.agirgol.ledger.api;

import io.github.agirgol.ledger.domain.AccountId;
import io.github.agirgol.ledger.domain.Entry;
import io.github.agirgol.ledger.domain.Money;
import io.github.agirgol.ledger.domain.Side;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * One side of a transaction.
 *
 * <p>{@code side} is DEBIT or CREDIT and {@code amount} is a magnitude. There
 * is no sign: a negative debit and a credit are different facts, and collapsing
 * them into one signed number is how a ledger loses the ability to say which
 * one happened.
 */
public record EntryView(
        @NotBlank String account,
        @NotBlank @Pattern(regexp = "DEBIT|CREDIT", message = "must be DEBIT or CREDIT")
                String side,
        @NotBlank String amount,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be a three-letter ISO 4217 code")
                String currency) {

    public static EntryView of(Entry entry) {
        return new EntryView(
                entry.account().value(),
                entry.side().name(),
                entry.amount().amount().toPlainString(),
                entry.amount().currency().getCurrencyCode());
    }

    public Entry toDomain() {
        java.util.Currency denomination = Currencies.of(currency);
        AccountId target = AccountId.of(account);
        return Side.valueOf(side) == Side.DEBIT
                ? Entry.debit(target, money(denomination))
                : Entry.credit(target, money(denomination));
    }

    /**
     * <p>{@code Money} rounds nothing: an amount carrying more decimal places
     * than its currency has is rejected rather than quietly trimmed, because
     * the trimmed digits are somebody's money. The exception that enforces it
     * is an {@link ArithmeticException} about scale, which is true and useless
     * to whoever sent the request, so it is restated here in terms of what they
     * sent.
     */
    private Money money(java.util.Currency denomination) {
        try {
            return Money.of(amount, denomination);
        } catch (ArithmeticException tooPrecise) {
            throw new IllegalArgumentException(
                    "%s %s carries more decimal places than %s has (%d)."
                            .formatted(amount, currency, currency,
                                    denomination.getDefaultFractionDigits()));
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(
                    "'%s' is not a decimal amount.".formatted(amount));
        }
    }
}
