plugins {
    id("billing.flink-job")
    id("billing.docker-tests")
}

dependencies {
    // Reuses the aggregator's resolve/dedupe stages, HourlyUsage and the metering schema.
    implementation(project(":jobs:usage-aggregator"))
    implementation(project(":libs:contracts"))
    implementation(project(":libs:rating"))
    implementation(libs.postgresql)

    testImplementation(libs.flyway.core) // MeteringMigrations returns a Flyway type
    testImplementation(libs.flink.test.utils)
    testImplementation(testFixtures(project(":libs:rating")))
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}

tasks.jar { manifest { attributes("Main-Class" to "com.logicvoid0x.billing.usagereplay.UsageReplayJob") } }
