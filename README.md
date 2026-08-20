# ledger-core

A double-entry ledger for the JVM. Immutable, append-only, and unable to hold an
unbalanced set of books.

[![ci](https://github.com/agirgol/ledger-core/actions/workflows/ci.yml/badge.svg)](https://github.com/agirgol/ledger-core/actions/workflows/ci.yml)

## The guarantee, checked against inputs nobody chose

```java
@Property
void the_books_balance_after_any_history(@ForAll("histories") List<Transaction> history) {
    Journal journal = post(history);

    for (Currency currency : CURRENCIES) {
        assertThat(journal.isBalanced(currency)).isTrue();
    }
}
```

This is not an example. It is a claim about **every** sequence of transactions,
so it is tested against generated ones — a thousand histories per property, per
build, with [jqwik](https://jqwik.net) shrinking any counterexample to the
smallest case that still fails.

Sixteen properties run this way. Among them: money forms an abelian group under
addition, posting order never changes a total, a reversal returns every balance
to exactly where it was, and summed across all accounts every movement nets to
zero. That last one is the accounting equation, stated without reference to
account types — if it ever failed, value would have entered the system without a
counterparty.

## What it refuses to do

Most of the design is refusal. Each of these is a way a ledger goes subtly,
invisibly wrong:

- **An unbalanced transaction cannot be constructed.** Not validated on save, not
  checked by a service — the constructor throws. There is no state in which the
  books are wrong and something intends to fix them later.
- **Every currency balances on its own.** Lira debits offset by dollar credits
  net to zero only if you assume a rate, and a ledger that assumes one has
  invented a number. Cross-currency movement is an explicit conversion, so the
  rate used is part of the record.
- **Never `double`.** Binary floating point cannot represent 0.10. In a ledger
  those fractions do not cancel out, they accumulate.
- **Amounts are positive; direction is the side.** A negative debit and a
  positive credit are not the same fact, even when they move a balance the same
  way — one is a correction, the other an ordinary posting.
- **Nothing is deleted.** A correction is a reversing transaction appended after
  the fact. Both postings stand, which is what makes the history auditable
  rather than merely current.
- **A balance is never computed across currencies.** An entry against an account
  in the wrong currency raises rather than being skipped — skipping produces a
  figure that looks fine and is wrong by exactly the amount skipped.

## The domain depends on nothing

```
$ ./gradlew :ledger-domain:dependencies --configuration compileClasspath

compileClasspath - Compile classpath for source set 'main'.
No dependencies
```

No Spring, no JPA, no annotation library. A ledger's rules are arithmetic and
invariants; they should be testable without a container starting and reusable
from a batch job or an ERP that has never heard of Spring.

An ArchUnit test fails the build if that stops being true — and it ships with a
demonstration that it can fail, because a boundary check that has only ever been
seen passing is not evidence of anything.

## Layout

| Module | Depends on | What it holds |
|---|---|---|
| `ledger-domain` | nothing | `Money`, `Account`, `Entry`, `Transaction`, `Journal` |
| `ledger-persistence` | domain | JPA mappings, Flyway migrations |
| `ledger-app` | persistence | A runnable application over the library |

## Status

Early. The domain model is in place and covered; persistence is scaffolded but
empty.

| | |
|---|---|
| `Money` — value object, currency-safe arithmetic | ✅ |
| `Transaction` — balance invariant, multi-currency, reversal | ✅ |
| `Journal` — balances, point-in-time balances | ✅ |
| Property-based test suite (16 properties) | ✅ |
| Architecture test enforcing the domain boundary | ✅ |
| Persistence: Flyway schema, JPA mappings, optimistic locking | 🚧 |
| Idempotency keys | ⬜ |
| JMH benchmarks (balance over 10K / 100K / 1M entries) | ⬜ |
| Spring Modulith boundary tests | ⬜ |

## Building

```sh
./gradlew build
```

Java 21. Persistence tests use Testcontainers against real Postgres rather than
H2 — a ledger leans on numeric precision and isolation semantics that an
in-memory database with different rules would not exercise.

## Licence

MIT
