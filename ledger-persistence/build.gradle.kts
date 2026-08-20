plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencyManagement {
    imports {
        // Spring Boot's BOM pins Testcontainers too, so no second import: the
        // 2.x line dropped the per-database modules this build needs, and
        // importing it would shadow the version Boot actually tests against.
        mavenBom(libs.spring.boot.bom.get().toString())
    }
}

dependencies {
    api(project(":ledger-domain"))

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // Spring Boot 4 splits Flyway autoconfiguration into its own artifact;
    // flyway-core alone brings the library but nothing that runs it at startup.
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Testcontainers rather than H2: the ledger relies on Postgres semantics —
    // numeric precision, transaction isolation, unique constraints under
    // concurrency — and a test that passes on an in-memory database with
    // different rules proves nothing about production.
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> { options.compilerArgs.add("-Xlint:deprecation") }
