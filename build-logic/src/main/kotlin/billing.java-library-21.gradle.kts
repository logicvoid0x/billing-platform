// Libraries used only by services (libs:admission, libs:testing): Java 21 toolchain.
plugins {
    `java-library`
    id("billing.java-conventions")
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
