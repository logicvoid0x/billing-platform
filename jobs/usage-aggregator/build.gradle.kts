plugins { id("billing.flink-job") }

dependencies {
    implementation(project(":libs:contracts"))
    implementation(project(":libs:rating"))
    implementation(libs.flink.connector.base)
    implementation(libs.flink.connector.kafka)

    testImplementation(libs.flink.test.utils)
    testImplementation(variantOf(libs.flink.streaming) { classifier("tests") }) // operator harnesses
    testImplementation(testFixtures(project(":libs:rating")))
    testImplementation(testFixtures(project(":libs:money")))
}

tasks.jar { manifest { attributes("Main-Class" to "com.logicvoid0x.billing.usageaggregator.UsageAggregatorJob") } }
