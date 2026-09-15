plugins {
    id("billing.java-service-21")
    id("billing.docker-tests")
}

dependencies {
    implementation(project(":libs:domain"))
    implementation(project(":libs:contracts")) // UsageEventCodec, GatewayLogEntry
    implementation(libs.kafka.clients)
    implementation(libs.iceberg.core)
    implementation(libs.iceberg.data) // generic reads of the raw archive (billing.usage_raw)
    runtimeOnly(libs.iceberg.parquet) // the archive is Parquet in production
    runtimeOnly(libs.hadoop.client.api)
    runtimeOnly(libs.hadoop.client.runtime)
    testImplementation(testFixtures(project(":libs:money")))
    testImplementation(testFixtures(project(":libs:rating"))) // golden catalog for the scenario
    testImplementation(project(":libs:rating"))
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.kafka)
}
