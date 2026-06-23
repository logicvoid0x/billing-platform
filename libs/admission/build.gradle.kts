plugins { id("billing.java-library-21") }

dependencies {
    api(project(":libs:money"))
    api(project(":libs:domain"))
    api(project(":libs:rating"))
    testImplementation(testFixtures(project(":libs:money")))
}
