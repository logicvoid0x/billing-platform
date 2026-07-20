plugins {
    id("billing.flink-job")
    id("billing.docker-tests")
}

dependencies {
    implementation(project(":libs:contracts"))
    implementation(project(":libs:rating"))
    implementation(libs.flink.connector.base)
    implementation(libs.flink.connector.kafka)
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)

    testImplementation(libs.flink.test.utils)
    testImplementation(variantOf(libs.flink.streaming) { classifier("tests") }) // operator harnesses
    testImplementation(testFixtures(project(":libs:rating")))
    testImplementation(testFixtures(project(":libs:money")))
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}

tasks.jar { manifest { attributes("Main-Class" to "com.logicvoid0x.billing.usageaggregator.UsageAggregatorJob") } }
