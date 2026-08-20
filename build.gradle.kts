/*
 * Root build: shared coordinates only.
 *
 * Each module declares its own dependencies rather than inheriting them from a
 * `subprojects` block. That is more repetition and less magic, and the
 * repetition is the point here: the whole argument of this library is that
 * `ledger-domain` depends on nothing, and a reader should be able to confirm
 * that by opening one file rather than reasoning about what a parent block
 * injected into it.
 */
allprojects {
    group = "io.github.agirgol"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}
