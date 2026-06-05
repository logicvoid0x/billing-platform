plugins { id("billing.java-library-17") }

dependencies { api(project(":libs:money")) }

dependencies { testImplementation(testFixtures(project(":libs:money"))) }
