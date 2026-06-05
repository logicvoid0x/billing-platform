plugins { id("billing.java-service-21") }

dependencies { testImplementation(testFixtures(project(":libs:money"))) }
