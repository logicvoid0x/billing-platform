// Libraries that also run inside Flink: Java 17 toolchain, so no Java 21 API can slip in.
plugins {
    `java-library`
    id("billing.java-conventions")
}

java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
