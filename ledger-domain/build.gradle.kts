plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

/*
 * Note what is absent: Spring, JPA, Jackson, Lombok, any annotation library.
 *
 * A double-entry ledger's rules are arithmetic and invariants. They should be
 * expressible without a container, testable without one starting up, and
 * reusable from a batch job or an ERP that has never heard of Spring. The
 * ArchUnit test in this module fails the build if that ever stops being true.
 */
dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.jqwik)
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
