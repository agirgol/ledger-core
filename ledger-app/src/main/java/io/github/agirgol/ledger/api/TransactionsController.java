package io.github.agirgol.ledger.api;

import io.github.agirgol.ledger.domain.Entry;
import io.github.agirgol.ledger.domain.Transaction;
import io.github.agirgol.ledger.domain.TransactionId;
import io.github.agirgol.ledger.persistence.LedgerStore;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transactions")
class TransactionsController {

    private final LedgerStore ledger;

    TransactionsController(LedgerStore ledger) {
        this.ledger = ledger;
    }

    /**
     * Posts a transaction.
     *
     * <p>Nothing here checks that it balances. It cannot be constructed
     * unbalanced, so by the time the request body has become a
     * {@link Transaction} the question is already settled — and if it could not
     * become one, the refusal below says by how much and in which currency.
     *
     * <p>Send an {@code Idempotency-Key} header to make a retry safe. The
     * second call with the same key returns the transaction the first one
     * wrote, with 200 rather than 201, so a client that lost the response to a
     * timeout can ask again without posting the amount twice.
     */
    @PostMapping
    ResponseEntity<TransactionView> post(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TransactionView request) {

        Transaction submitted = request.toDomain();
        requireAccountsExist(submitted);

        Transaction stored = idempotencyKey == null || idempotencyKey.isBlank()
                ? ledger.post(submitted)
                : ledger.post(submitted, idempotencyKey);

        boolean replayed = !stored.id().equals(submitted.id());
        return ResponseEntity.status(replayed ? HttpStatus.OK : HttpStatus.CREATED)
                .location(URI.create("/transactions/" + stored.id().value()))
                .body(TransactionView.of(stored));
    }

    @GetMapping("/{id}")
    TransactionView find(@PathVariable String id) {
        return ledger.findTransaction(TransactionId.of(id))
                .map(TransactionView::of)
                .orElseThrow(() -> new NoSuchTransactionException(id));
    }

    /**
     * Refuses a transaction that posts against an account nobody opened.
     *
     * <p>The foreign key would refuse it too, and it remains the guard that
     * actually holds under concurrency. This check exists for the message: a
     * constraint violation says a row could not be inserted, where the caller
     * needs to know which of the account ids they sent does not exist.
     */
    private void requireAccountsExist(Transaction transaction) {
        Set<String> referenced = new LinkedHashSet<>();
        for (Entry entry : transaction.entries()) {
            referenced.add(entry.account().value());
        }
        List<String> missing = new ArrayList<>();
        for (String id : referenced) {
            if (ledger.findAccount(id).isEmpty()) {
                missing.add(id);
            }
        }
        if (!missing.isEmpty()) {
            throw new NoSuchAccountException(missing);
        }
    }
}
