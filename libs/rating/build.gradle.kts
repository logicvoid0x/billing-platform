plugins { id("billing.java-library-17") }

dependencies {
    api(project(":libs:money"))
    api(project(":libs:domain"))
}

dependencies { testImplementation(testFixtures(project(":libs:money"))) }
