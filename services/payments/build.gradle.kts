plugins { id("billing.java-service-21") }

dependencies {
    implementation(libs.spring.boot.starter.webmvc) // versions from the Spring Boot BOM (Jackson 3)
    testImplementation(testFixtures(project(":libs:money")))
}
