package io.github.agirgol.ledger.domain;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The domain depends on the JDK and nothing else.
 *
 * <p>This is the boundary the whole library is organised around, and a boundary
 * that exists only in a README is one that erodes on the first afternoon
 * somebody needs an annotation. Here it fails the build.
 *
 * <p>What it buys: the ledger's rules can be exercised without a container
 * starting, reused from a batch job or an ERP that has never heard of Spring,
 * and read by someone who does not know the framework. It also keeps the
 * temptation out — an {@code @Entity} on an aggregate is how persistence
 * concerns end up deciding what a valid transaction looks like.
 */
@AnalyzeClasses(
        packages = "io.github.agirgol.ledger.domain",
        importOptions = ImportOption.DoNotIncludeTests.class)
class DomainPurityTest {

    @ArchTest
    static final ArchRule domain_uses_only_the_jdk = noClasses()
            .should()
            .dependOnClassesThat()
            .resideOutsideOfPackages(
                    "java..",
                    "javax..",
                    "io.github.agirgol.ledger.domain..")
            .because("the domain must stay framework-free: no Spring, no JPA, no annotation library. "
                    + "Its rules are arithmetic and invariants, and those should be testable "
                    + "without a container and reusable outside one.");
}
