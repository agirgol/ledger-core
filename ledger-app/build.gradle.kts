plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation(project(":ledger-persistence"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    // Bean Validation rejects a malformed body with a 400 before any of it
    // reaches the domain, which keeps the domain's own refusals meaning what
    // they say: not "you sent nonsense" but "this does not balance".
    implementation("org.springframework.boot:spring-boot-starter-validation")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 4 moved the MockMvc slice out of the umbrella test starter
    // into a per-technology artifact; the starter alone no longer carries
    // @AutoConfigureMockMvc.
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
