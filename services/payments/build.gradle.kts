plugins {
    id("billing.java-service-21")
    id("billing.docker-tests")
}

dependencies {
    implementation(project(":libs:contracts")) // BudgetChange for budget.changes
    implementation(libs.spring.boot.starter.webmvc) // versions from the Spring Boot BOM (Jackson 3)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(testFixtures(project(":libs:money")))

    // End-to-end: the webhook posts top-ups to the real balance-ledger service (Q10).
    testImplementation(project(":services:balance-ledger"))
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}
