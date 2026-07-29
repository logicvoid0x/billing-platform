// Kafka Connect plugins: Java 21 library shaded into one jar for the Connect image (infra/connect).
plugins {
    id("billing.java-library-21")
    id("com.gradleup.shadow")
}
