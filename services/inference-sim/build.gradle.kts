plugins { id("billing.java-service-21") }

dependencies {
    implementation(project(":libs:domain"))
    implementation(project(":libs:contracts"))
    implementation(libs.kafka.clients)
    implementation(libs.spring.boot.starter.webmvc) // versions from the Spring Boot BOM
    implementation(libs.spring.boot.starter.actuator)
    runtimeOnly(libs.micrometer.registry.prometheus)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.kafka)
}
