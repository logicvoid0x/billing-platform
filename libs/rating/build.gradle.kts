plugins {
    id("billing.java-library-17")
    `java-test-fixtures`
}

dependencies {
    api(project(":libs:money"))
    api(project(":libs:domain"))
}

dependencies { testImplementation(testFixtures(project(":libs:money"))) }

