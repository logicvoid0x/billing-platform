plugins { id("billing.java-library-21") }

dependencies {
    api(project(":libs:money"))
    api(project(":libs:domain"))
    api(project(":libs:rating"))
    testImplementation(testFixtures(project(":libs:money")))

    // JMH benchmark run from a JUnit test (AdmissionBenchmarkTest), so `build` checks the budget.
    testImplementation(libs.jmh.core)
    testAnnotationProcessor(libs.jmh.generator.annprocess)
}

// JMH's generated benchmark code trips javac lint (processing, rawtypes); keep -Werror elsewhere.
tasks.named<JavaCompile>("compileTestJava") {
    options.compilerArgs.addAll(listOf("-Xlint:-processing", "-Xlint:-rawtypes", "-Xlint:-unchecked"))
}
