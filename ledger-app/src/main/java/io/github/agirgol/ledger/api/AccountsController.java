package io.github.agirgol.ledger.api;

import io.github.agirgol.ledger.domain.Account;
import io.github.agirgol.ledger.persistence.LedgerStore;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
class AccountsController {

    private final LedgerStore ledger;

    AccountsController(LedgerStore ledger) {
        this.ledger = ledger;
    }

    /**
     * Opens an account, or returns the one already open under that id.
     *
     * <p>200 rather than 201, always. Opening is idempotent in the library — a
     * redeploy or a retried setup step calls it again — so there is no state in
     * which this fails with "already exists", and reporting "created" on a call
     * that created nothing would be a lie the client cannot check.
     */
    @PostMapping
    AccountView open(@Valid @RequestBody AccountView request) {
        return AccountView.of(ledger.openAccount(request.toDomain()));
    }

    @GetMapping("/{id}")
    AccountView find(@PathVariable String id) {
        return ledger.findAccount(id)
                .map(AccountView::of)
                .orElseThrow(() -> new NoSuchAccountException(id));
    }

    /**
     * The balance, optionally as it stood at a moment.
     *
     * <p>Without {@code asOf} the answer is "now", and now is pinned once here
     * rather than left to the query: two entries posted while the request is in
     * flight should not land on different sides of the same balance.
     */
    @GetMapping("/{id}/balance")
    BalanceView balance(
            @PathVariable String id,
            @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf) {
        Account account = ledger.findAccount(id)
                .orElseThrow(() -> new NoSuchAccountException(id));
        Instant moment = asOf == null ? Instant.now() : asOf;
        return new BalanceView(id, moment, MoneyView.of(ledger.balanceOf(account, moment)));
    }
}
