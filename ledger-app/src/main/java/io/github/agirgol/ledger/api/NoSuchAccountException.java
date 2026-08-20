package io.github.agirgol.ledger.api;

import java.util.List;

/** Names the accounts a request referred to that have not been opened. */
class NoSuchAccountException extends RuntimeException {

    private final transient List<String> ids;

    NoSuchAccountException(List<String> ids) {
        super(ids.size() == 1
                ? "No account '%s' has been opened.".formatted(ids.getFirst())
                : "These accounts have not been opened: %s.".formatted(String.join(", ", ids)));
        this.ids = List.copyOf(ids);
    }

    NoSuchAccountException(String id) {
        this(List.of(id));
    }

    List<String> ids() {
        return ids;
    }
}
