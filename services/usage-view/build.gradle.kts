plugins {
    id("billing.java-service-21")
    id("billing.docker-tests")
}

dependencies {
    implementation(project(":libs:domain"))
    implementation(project(":libs:contracts")) // catalog snapshot client: SKU -> model (Q7)
    implementation(libs.spring.boot.starter.webmvc) // versions from the Spring Boot BOM
    implementation(libs.spring.boot.starter.jdbc)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(testFixtures(project(":libs:money")))
    testImplementation(testFixtures(project(":libs:rating")))
    // Writes usage through the aggregator's own sink and migrations (Q12 b).
    testImplementation(project(":jobs:usage-aggregator"))
    testImplementation(libs.flink.streaming) // the sink class implements Flink interfaces
    testImplementation(libs.flyway.core) // MeteringMigrations
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.postgresql)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}
