package io.github.agirgol.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A runnable application over the library, not the library itself.
 *
 * <p>`ledger-domain` and `ledger-persistence` are what a consumer depends on.
 * This module exists so the library can be demonstrated end to end, and nothing
 * here is on the path of a caller using it as a library.
 *
 * <p>It sits in the root package deliberately. The library's classes are in
 * sibling packages — `domain`, `persistence`, `api` — and component, entity and
 * repository scanning all start from the application's own package, so placing
 * it one level down would mean naming each of them in an annotation and
 * updating that list every time a package is added.
 */
@SpringBootApplication
public class LedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerApplication.class, args);
    }
}
