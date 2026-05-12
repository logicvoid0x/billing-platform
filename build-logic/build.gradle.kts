plugins { `kotlin-dsl` }

dependencies {
    implementation(libs.gradle.spring.boot)
    implementation(libs.gradle.spring.dependency.management)
    implementation(libs.gradle.shadow)
}
