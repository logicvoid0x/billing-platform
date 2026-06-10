plugins { id("billing.java-service-21") }

dependencies {
    implementation(project(":libs:domain"))
    implementation(libs.spring.boot.starter.webmvc) // version from the Spring Boot BOM
    testImplementation(libs.spring.boot.starter.test)
}
