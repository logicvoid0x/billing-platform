plugins {
    id("billing.java-service-21")
    id("billing.docker-tests")
}

dependencies {
    implementation(project(":libs:money"))
    implementation(libs.spring.boot.starter.webmvc) // POST /v1/top-ups (Q10); versions from the Boot BOM
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(testFixtures(project(":libs:money")))
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}
