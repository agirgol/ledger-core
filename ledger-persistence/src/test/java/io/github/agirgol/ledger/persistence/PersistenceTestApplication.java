package io.github.agirgol.ledger.persistence;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A context for this module's tests.
 *
 * <p>The persistence module is a library, not an application, so it has no
 * entry point of its own. Spring's test slices need one to locate
 * configuration, and putting it in test sources keeps it out of what consumers
 * depend on.
 */
@SpringBootApplication
class PersistenceTestApplication {
}
