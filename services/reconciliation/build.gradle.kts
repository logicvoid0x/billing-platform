plugins {
    id("billing.java-service-21")
    id("billing.docker-tests")
}

dependencies {
    implementation(project(":libs:domain"))
    implementation(project(":libs:contracts")) // UsageEventCodec, GatewayLogEntry
    implementation(libs.kafka.clients)
    testImplementation(testFixtures(project(":libs:money")))
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.kafka)
}
