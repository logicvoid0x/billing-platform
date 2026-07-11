// Modules whose tests start Docker containers (Testcontainers). Their test tasks share one build
// service with a single slot, so with org.gradle.parallel=true at most one of them runs at a time:
// parallel container-heavy suites starve Docker Desktop and make broker kill/restart tests flaky.
abstract class DockerTestsLock : BuildService<BuildServiceParameters.None>

val dockerTests =
    gradle.sharedServices.registerIfAbsent("dockerTests", DockerTestsLock::class) {
        maxParallelUsages = 1
    }

tasks.withType<Test>().configureEach { usesService(dockerTests) }
