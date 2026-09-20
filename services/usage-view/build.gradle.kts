plugins { id("billing.java-service-21") }

dependencies {
    implementation(project(":libs:domain"))
    testImplementation(testFixtures(project(":libs:money")))
}
