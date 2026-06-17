plugins { id("billing.java-service-21") }

dependencies {
    implementation(project(":libs:domain"))
    implementation(project(":libs:contracts"))
    implementation(libs.ratis.server)
    implementation(libs.ratis.grpc)
    runtimeOnly(libs.ratis.metrics.default)
    testImplementation(testFixtures(project(":libs:money")))
    testImplementation(libs.grpc.inprocess)
}
