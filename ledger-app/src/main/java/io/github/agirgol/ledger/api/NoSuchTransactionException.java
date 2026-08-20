package io.github.agirgol.ledger.api;

/** Nothing has been posted under that id. */
class NoSuchTransactionException extends RuntimeException {

    NoSuchTransactionException(String id) {
        super("No transaction '%s' has been posted.".formatted(id));
    }
}
