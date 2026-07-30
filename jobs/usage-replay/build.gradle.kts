plugins {
    id("billing.flink-job")
    id("billing.docker-tests")
}

dependencies {
    // Reuses the aggregator's resolve/dedupe stages, HourlyUsage and the metering schema.
    implementation(project(":jobs:usage-aggregator"))
    implementation(project(":libs:contracts"))
    implementation(project(":libs:rating"))
    implementation(libs.iceberg.flink.runtime) // IcebergSource for the batch read of billing.usage_raw
    // Iceberg catalog/table loaders take a Hadoop Configuration; the shaded client keeps it small.
    implementation(libs.hadoop.client.api)
    runtimeOnly(libs.hadoop.client.runtime)
    flinkProvided(libs.flink.table.runtime) // in the Flink distribution (lib/)
    flinkProvided(libs.flink.table.api.java.bridge)
    flinkProvided(libs.flink.connector.files) // Iceberg readers use its pools; in Flink lib/
    implementation(libs.postgresql)

    testImplementation(libs.flyway.core) // MeteringMigrations returns a Flyway type
    testImplementation(libs.iceberg.data) // writes archive rows in tests
    testImplementation(libs.flink.test.utils)
    testImplementation(testFixtures(project(":libs:rating")))
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}

tasks.jar { manifest { attributes("Main-Class" to "com.logicvoid0x.billing.usagereplay.UsageReplayJob") } }
