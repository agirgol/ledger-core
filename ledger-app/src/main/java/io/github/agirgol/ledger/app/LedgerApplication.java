package io.github.agirgol.ledger.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A runnable application over the library, not the library itself.
 *
 * <p>`ledger-domain` and `ledger-persistence` are what a consumer depends on.
 * This module exists so the library can be demonstrated end to end, and nothing
 * here is on the path of a caller using it as a library.
 */
@SpringBootApplication
public class LedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerApplication.class, args);
    }
}
