package io.github.agirgol.ledger.api;

import io.github.agirgol.ledger.domain.Account;
import io.github.agirgol.ledger.domain.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * An account, in both directions.
 *
 * <p>The request and response shapes are identical, so there is one record
 * rather than two that would have to be kept in step.
 */
public record AccountView(
        @NotBlank String id,
        @NotBlank String name,
        @NotBlank String type,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be a three-letter ISO 4217 code")
                String currency) {

    public static AccountView of(Account account) {
        return new AccountView(
                account.id().value(),
                account.name(),
                account.type().name(),
                account.currency().getCurrencyCode());
    }

    public Account toDomain() {
        return Account.of(id, name, accountType(), Currencies.of(currency));
    }

    /**
     * The five account classes, named rather than guessed at.
     *
     * <p>{@code valueOf} would refuse an unknown type with a message naming the
     * constant it could not find, which tells the caller what they typed and
     * not what they could have typed instead.
     */
    private AccountType accountType() {
        try {
            return AccountType.valueOf(type);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(
                    "'%s' is not an account type. Use one of ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE."
                            .formatted(type));
        }
    }
}
