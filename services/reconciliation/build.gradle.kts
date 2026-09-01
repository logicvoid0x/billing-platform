plugins { id("billing.java-service-21") }

dependencies {
    implementation(project(":libs:domain"))
    implementation(project(":libs:contracts")) // UsageEventCodec, GatewayLogEntry
    implementation(libs.kafka.clients)
    testImplementation(testFixtures(project(":libs:money")))
}
