plugins {
    java
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation(project(":ledger-domain"))

    /*
     * The persistence module is here for one reason: its Flyway migration. The
     * stored benchmark has to run against the schema the library actually
     * ships — indexes and all — because a hand-copied schema drifts from the
     * real one and then measures a database nobody deploys.
     */
    implementation(project(":ledger-persistence"))
    implementation(platform(libs.spring.boot.bom))
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql")
    implementation("org.testcontainers:testcontainers-postgresql")

    implementation(libs.jmh.core)
    annotationProcessor(libs.jmh.annprocess)
}

/*
 * JMH is wired by hand rather than through a plugin. The plugin would generate
 * this task and a second source set; what it would not do is make it obvious
 * which JVM flags the measurements were taken under, and for a benchmark that
 * is the part worth reading.
 */
tasks.register<JavaExec>("jmh") {
    group = "verification"
    description = "Runs the JMH benchmarks."
    mainClass = "org.openjdk.jmh.Main"
    classpath = sourceSets["main"].runtimeClasspath

    // A million entries held as domain objects is a few hundred megabytes; the
    // default heap would spend the run in GC and measure that instead.
    jvmArgs = listOf("-Xmx6g", "-Xms6g")

    args = (project.findProperty("jmhArgs") as String?)
        ?.split(" ")
        ?.filter { it.isNotBlank() }
        ?: listOf()
}
