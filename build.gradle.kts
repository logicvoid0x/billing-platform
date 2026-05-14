// Root build: guard rails that span modules.

// Toolchain and dependency facts are read from every module, so evaluate them first.
subprojects.forEach { evaluationDependsOn(it.path) }

/** Modules that run inside Flink (or feed it) and must stay on Java 17. */
fun requiredJava(path: String): Int =
    if (path in setOf(":libs:money", ":libs:domain", ":libs:contracts", ":libs:rating") ||
        path.startsWith(":jobs:")
    ) 17 else 21

abstract class VerifyToolchains : DefaultTask() {
    /** Module path -> configured toolchain language version. */
    @get:Input abstract val actual: MapProperty<String, Int>

    /** Module path -> required toolchain language version. */
    @get:Input abstract val required: MapProperty<String, Int>

    /** Module path -> project dependencies it declares (any configuration). */
    @get:Input abstract val projectDeps: MapProperty<String, List<String>>

    @TaskAction
    fun verify() {
        val actual = actual.get()
        val required = required.get()
        val errors = mutableListOf<String>()
        actual.toSortedMap().forEach { (path, version) ->
            val want = required.getValue(path)
            logger.lifecycle("  %-28s Java %d".format(path, version))
            if (version != want) errors += "$path uses Java $version, must use Java $want"
            if (version != 17 && version != 21) errors += "$path uses Java $version; only 17 and 21 are allowed"
        }
        projectDeps.get().forEach { (from, deps) ->
            deps.filter { (actual[from] ?: 0) == 17 && (actual[it] ?: 0) == 21 }
                .forEach { errors += "$from (Java 17) depends on $it (Java 21)" }
        }
        if (errors.isNotEmpty()) throw GradleException("verifyToolchains failed:\n  " + errors.joinToString("\n  "))
        logger.lifecycle("verifyToolchains: ${actual.size} modules OK")
    }
}

val javaModules = subprojects.filter { it.plugins.hasPlugin("java") }

val verifyToolchains = tasks.register<VerifyToolchains>("verifyToolchains") {
    group = "verification"
    description = "Checks Java 17 for Flink-bound modules, 21 elsewhere, and no 17 -> 21 dependencies."
    javaModules.forEach { p ->
        actual.put(p.path, p.extensions.getByType<JavaPluginExtension>().toolchain.languageVersion.get().asInt())
        required.put(p.path, requiredJava(p.path))
        projectDeps.put(
            p.path,
            p.configurations
                .flatMap { c -> c.dependencies.withType<ProjectDependency>().map { it.path } }
                .distinct()
                .sorted(),
        )
    }
}

tasks.register("check") {
    group = "verification"
    dependsOn(verifyToolchains)
}

tasks.register("build") {
    group = "build"
    dependsOn("check")
}
