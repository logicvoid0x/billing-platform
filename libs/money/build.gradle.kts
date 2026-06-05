plugins {
    id("billing.java-library-17")
    `java-test-fixtures`
}

dependencies {
    // MoneyRules: the shared ArchUnit + source rule for every money-bearing module.
    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.archunit.junit5)
    testFixturesApi(libs.junit.jupiter)
    testFixturesApi(libs.assertj.core)
}
