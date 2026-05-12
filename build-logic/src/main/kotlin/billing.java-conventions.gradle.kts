// Shared by every Java module: repositories, compiler flags, JUnit 5 + AssertJ + jqwik.
// Not applied directly; the toolchain-specific plugins below apply it.
plugins { java }

val libs = the<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()

repositories { mavenCentral() }

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror", "-parameters"))
}

dependencies {
    testImplementation(platform(lib("junit-bom")))
    testImplementation(lib("junit-jupiter"))
    testImplementation(lib("assertj-core"))
    testImplementation(lib("jqwik"))
    testRuntimeOnly(lib("junit-platform-launcher"))
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
