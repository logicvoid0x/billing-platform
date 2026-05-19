// Shared by every Java module: repositories, compiler flags, tests, Spotless, Error Prone, JaCoCo.
// Not applied directly; the toolchain-specific plugins apply it and then set the toolchain.
import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
    jacoco
    id("com.diffplug.spotless")
    id("net.ltgt.errorprone")
}

val libs = the<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()
fun version(alias: String) = libs.findVersion(alias).get().requiredVersion

repositories { mavenCentral() }

dependencies {
    testImplementation(platform(lib("junit-bom")))
    testImplementation(lib("junit-jupiter"))
    testImplementation(lib("assertj-core"))
    testImplementation(lib("jqwik"))
    testRuntimeOnly(lib("junit-platform-launcher"))
}

// Error Prone 2.43+ runs only on JDK 21, so Java 17 modules use the last release that runs on 17.
val errorproneCore17 = lib("errorprone-core-jdk17")
val errorproneCore = lib("errorprone-core")
dependencies.addProvider(
    "errorprone",
    java.toolchain.languageVersion.flatMap { if (it.asInt() < 21) errorproneCore17 else errorproneCore },
)

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror", "-parameters"))
    options.errorprone {
        disableWarningsInGeneratedCode = true
        excludedPaths = ".*/build/generated/.*"
    }
}

spotless {
    java {
        target("src/*/java/**/*.java")
        googleJavaFormat(version("googleJavaFormat"))
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

jacoco { toolVersion = version("jacoco") }

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.check { dependsOn(tasks.jacocoTestReport) }
