// Spring Boot services: Java 21 toolchain; application.yml sets spring.threads.virtual.enabled=true.
plugins {
    java
    id("billing.java-conventions")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter") // version from the Spring Boot BOM
}
