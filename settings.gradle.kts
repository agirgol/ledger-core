rootProject.name = "ledger-core"

/*
 * Three modules, split by what each is allowed to depend on.
 *
 * `domain` is the point of the exercise: plain Java, no Spring, no JPA, no
 * annotations from anything. A double-entry ledger's rules are arithmetic and
 * invariants, and those should be expressible — and testable — without a
 * container starting up. An ArchUnit test enforces it, because a boundary that
 * is only documented is a boundary that erodes.
 */
include(
    "ledger-domain",
    "ledger-persistence",
    "ledger-app",
    "ledger-benchmarks",
)
