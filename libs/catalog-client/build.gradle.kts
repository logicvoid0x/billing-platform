// Client of the catalog snapshot API (open question Q7): runs in Flink jobs and Java 21 services.
plugins { id("billing.java-library-17") }

dependencies {
    api(project(":libs:rating"))
    api(project(":libs:contracts"))

    testImplementation(testFixtures(project(":libs:rating")))
}
