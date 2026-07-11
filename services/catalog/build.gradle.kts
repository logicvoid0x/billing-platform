plugins {
    id("billing.java-service-21")
    id("billing.docker-tests")
}

dependencies {
    implementation(project(":libs:rating"))
    implementation(libs.spring.boot.starter.webmvc) // versions from the Spring Boot BOM
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.postgresql)
    testImplementation(testFixtures(project(":libs:money")))
    testImplementation(testFixtures(project(":libs:rating")))
}
