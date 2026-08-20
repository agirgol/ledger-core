package io.github.agirgol.ledger.api;

import io.github.agirgol.ledger.domain.CurrencyMismatchException;
import io.github.agirgol.ledger.domain.Money;
import io.github.agirgol.ledger.domain.UnbalancedTransactionException;
import java.net.URI;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the library's refusals into responses that say what to do instead.
 *
 * <p>Every exception handled here is a refusal rather than a fault: the ledger
 * declines to store a transaction that does not balance, to add two currencies,
 * or to round away a decimal place somebody's money is in. Each is recoverable
 * by the caller, which is the whole reason the message has to survive the trip
 * out — left untranslated they reach the client as a 500, and a caller that
 * cannot tell a refusal from a crash retries the request that will never work.
 *
 * <p>They are 422 rather than 400. The request parsed, the fields were the
 * right types, and a schema validator would have passed it. What failed is the
 * accounting.
 */
@RestControllerAdvice
class LedgerProblemHandler {

    @ExceptionHandler(UnbalancedTransactionException.class)
    ProblemDetail unbalanced(UnbalancedTransactionException refusal) {
        ProblemDetail problem = problem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "unbalanced-transaction",
                "Transaction does not balance",
                refusal.getMessage());

        // Debits minus credits, per currency: positive means the debits are
        // ahead by that much. A caller told it is 40.00 TRY out knows what to
        // change; one told only that it does not balance has to work it out.
        Map<String, String> imbalance = new LinkedHashMap<>();
        for (Map.Entry<Currency, Money> residue : refusal.imbalance().entrySet()) {
            imbalance.put(
                    residue.getKey().getCurrencyCode(),
                    residue.getValue().amount().toPlainString());
        }
        problem.setProperty("imbalance", imbalance);
        return problem;
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    ProblemDetail currencyMismatch(CurrencyMismatchException refusal) {
        ProblemDetail problem = problem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "currency-mismatch",
                "Currencies cannot be combined",
                refusal.getMessage());
        problem.setProperty("left", refusal.left().getCurrencyCode());
        problem.setProperty("right", refusal.right().getCurrencyCode());
        return problem;
    }

    @ExceptionHandler(NoSuchAccountException.class)
    ProblemDetail noSuchAccount(NoSuchAccountException missing) {
        ProblemDetail problem = problem(
                HttpStatus.NOT_FOUND,
                "no-such-account",
                "Account not opened",
                missing.getMessage());
        problem.setProperty("accounts", missing.ids());
        return problem;
    }

    @ExceptionHandler(NoSuchTransactionException.class)
    ProblemDetail noSuchTransaction(NoSuchTransactionException missing) {
        return problem(
                HttpStatus.NOT_FOUND,
                "no-such-transaction",
                "Transaction not found",
                missing.getMessage());
    }

    /**
     * The domain's remaining refusals: an unknown account type, a currency code
     * that is not ISO 4217, an amount with more decimal places than its
     * currency has. All of them arrive already carrying a message written for
     * whoever sent the request.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail refused(IllegalArgumentException refusal) {
        return problem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "not-postable",
                "Cannot be posted",
                refusal.getMessage());
    }

    /**
     * A stored account whose entries are denominated in another currency.
     *
     * <p>409 rather than 422: nothing is wrong with the request. Something is
     * wrong with what is already in the book, and no correction to this call
     * will resolve it.
     */
    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail inconsistent(IllegalStateException conflict) {
        return problem(
                HttpStatus.CONFLICT,
                "inconsistent-ledger",
                "Stored entries conflict",
                conflict.getMessage());
    }

    private static ProblemDetail problem(
            HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("/problems/" + type));
        problem.setTitle(title);
        return problem;
    }
}
