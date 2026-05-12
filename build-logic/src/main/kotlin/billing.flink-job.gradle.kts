// Flink jobs: Java 17; Flink jars are provided by the cluster, connectors are shaded into the job jar.
plugins {
    java
    id("billing.java-conventions")
    id("com.gradleup.shadow")
}

java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }

val flinkProvided = configurations.dependencyScope("flinkProvided").get()
configurations.compileOnly { extendsFrom(flinkProvided) }
configurations.testImplementation { extendsFrom(flinkProvided) }
