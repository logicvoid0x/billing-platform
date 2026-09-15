plugins { id("billing.java-service-21") }

dependencies {
    implementation(project(":libs:rating"))
    testImplementation(testFixtures(project(":libs:money")))
    testImplementation(testFixtures(project(":libs:rating")))
}
