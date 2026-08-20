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

Spring Modulith was the obvious tool for this and is deliberately not used. Its
strong form declares a module's allowed dependencies in a `package-info.java`
annotation — which would put a Spring type in the one package whose whole claim
is that it has none, so the check would break what it checks. Its weaker form
looks for cycles between packages, and cycles are already impossible here: these
are separate Gradle modules, and `ledger-domain` declares no dependencies at
all, so the compiler settles it before any test runs.

## Two ways to read a balance, and what each costs

`Journal` replays every entry — the readable definition, and the one the
property tests hold to. `LedgerStore` aggregates in SQL. A test asserts the two
agree, because the moment they drift the fast one silently becomes wrong.

Agreement was never the interesting part; if they disagreed, one of them would
be a bug. The interesting part is the gap:

| entries on the account | aggregate in SQL | load the book and replay it |
|---|---|---|
| 10 000 | 1.0 ms | 13 ms |
| 100 000 | 8.1 ms | 144 ms |
| 1 000 000 | 60 ms | 1 939 ms |

JMH, average time, against Postgres 17 in Docker on Apple Silicon. Read the
replay column as an order of magnitude rather than a figure: a million domain
objects makes it an allocation benchmark as much as a balance one, and its
error bars are wide enough to say so.

That is the whole argument for `LedgerStore` carrying a second way to answer a
question the domain already answers.

## Layout

| Module | Depends on | What it holds |
|---|---|---|
| `ledger-domain` | nothing | `Money`, `Account`, `Entry`, `Transaction`, `Journal` |
| `ledger-persistence` | domain | JPA mappings, Flyway migrations |
| `ledger-app` | persistence | HTTP surface over the library |
| `ledger-benchmarks` | domain, persistence | JMH measurements of both balance paths |

## Append-only, enforced by the database

The library never updates or deletes an entry — a correction is a reversing
transaction. But "never" in application code means "never on this code path",
and an audit trail a stray `UPDATE` can rewrite is not an audit trail. So the
tables refuse:

```
ERROR: UPDATE on entries is not permitted: the ledger is append-only.
       Post a reversing transaction instead.
```

Two tests assert exactly that, by issuing the statements the library never
would.

## The benchmark changed the schema

A balance as of a moment first asked the database to join every entry of an
account back to `transactions` for its timestamp. Measured over a million
entries, that join cost 157 ms — against 52 ms for the same sum with no time
bound at all. Three times the arithmetic it qualified, spent on reaching a
column.

So V2 puts the timestamp on the entry, and the query stopped joining:

| entries on the account | via the join | on the entry |
|---|---|---|
| 10 000 | 2.8 ms | 1.0 ms |
| 100 000 | 21 ms | 8.1 ms |
| 1 000 000 | 157 ms | 60 ms |

At a million entries the point-in-time filter now costs 7 ms over the
unfiltered sum, where it used to cost 105. Both queries are still in the
benchmark — the old one as a control — so the claim above can be re-run rather
than believed.

Copying a column is normally a trade of correctness for speed, since the copy
can drift. This one cannot. Both tables reject `UPDATE`, so neither value can
change after it is written, and the application never writes the copy at all: a
`BEFORE INSERT` trigger derives it from the parent row and overwrites whatever
was supplied. Backfilling it meant taking the immutability trigger off for the
length of the migration, which is the only moment in this schema's life that an
entry has been allowed to change.

## The HTTP surface

`ledger-app` runs the library over HTTP. It is not part of what a consumer
depends on — the library is `ledger-domain` and `ledger-persistence` — and it
exists so the whole thing can be exercised end to end.

| | |
|---|---|
| `POST /accounts` | Open an account. Idempotent, so 200 rather than 201: reporting "created" on a call that created nothing is a claim the client cannot check. |
| `GET /accounts/{id}` | The account, or 404. |
| `GET /accounts/{id}/balance?asOf=` | The balance, optionally as it stood at a moment. |
| `POST /transactions` | Post a transaction. An `Idempotency-Key` header makes a retry safe; the second call returns the first one's transaction with 200. |
| `GET /transactions/{id}` | What was posted. |

Refusals come back as RFC 9457 problem documents, and they are 422 rather than
400. The request parsed, the fields were the right types, a schema validator
would have passed it — what failed is the accounting:

```http
HTTP/1.1 422 Unprocessable Content
Content-Type: application/problem+json

{
  "type": "/problems/unbalanced-transaction",
  "title": "Transaction does not balance",
  "detail": "Debits and credits do not balance. TRY: debits exceed credits by 40.00 TRY. Every currency in a transaction must balance on its own; a transaction that nets to zero across currencies is still two unbalanced books.",
  "imbalance": { "TRY": "40.00" }
}
```

Being told a transaction is unbalanced is not something a caller can act on.
Being told the debits are 40.00 TRY ahead is. The same applies to the rest:
an account that was never opened comes back named, an amount carrying more
decimal places than its currency has is refused rather than rounded, and a
currency code that is not ISO 4217 is rejected as such rather than as a lookup
failure.

Amounts cross the wire as strings. JSON would carry the decimal exactly, but a
client that parses it into a double would not, and a library that refuses
`double` internally has no business handing one out at its edge.

Nine tests cover this surface against a real Postgres, and seven of them assert
a refusal. Posting a balanced transaction is the easy half.

## Status

The domain model and persistence are in place and covered.

| | |
|---|---|
| `Money` — value object, currency-safe arithmetic | ✅ |
| `Transaction` — balance invariant, multi-currency, reversal | ✅ |
| `Journal` — balances, point-in-time balances | ✅ |
| Property-based test suite (16 properties) | ✅ |
| Architecture test enforcing the domain boundary | ✅ |
| Flyway schema with append-only triggers | ✅ |
| JPA mappings, optimistic locking on account metadata | ✅ |
| Idempotency keys, settled by a unique constraint | ✅ |
| Testcontainers suite against real Postgres | ✅ |
| JMH benchmarks (balance over 10K / 100K / 1M entries) | ✅ |
| HTTP API with RFC 9457 problem responses | ✅ |

## Building

```sh
./gradlew build
```

Java 21. Persistence tests use Testcontainers against real Postgres rather than
H2 — a ledger leans on numeric precision and isolation semantics that an
in-memory database with different rules would not exercise.

To run the HTTP application you supply a database. There are no datasource
defaults in `application.yaml` on purpose — a ledger that quietly connects to
whatever is listening on localhost is worse than one that refuses to start:

```sh
docker run -d --name ledger-db -p 5432:5432 \
  -e POSTGRES_DB=ledger -e POSTGRES_PASSWORD=ledger postgres:17-alpine

SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledger \
SPRING_DATASOURCE_USERNAME=postgres \
SPRING_DATASOURCE_PASSWORD=ledger \
./gradlew :ledger-app:bootRun
```

Flyway applies the schema on startup; Hibernate then validates its mappings
against it and fails fast if they disagree.

The benchmarks are not part of `build`; they take minutes and they are a
measurement, not a check:

```sh
./gradlew :ledger-benchmarks:jmh                                  # everything
./gradlew :ledger-benchmarks:jmh -PjmhArgs="JournalBalanceBenchmark"
```

The stored benchmarks refuse to start timing until every balance path returns
the same number, because a benchmark that measures the wrong answer still
produces a figure and the figure still ends up in a README.

## Licence

MIT
