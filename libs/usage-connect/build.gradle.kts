// Kafka Connect plugin: decodes usage.raw (billing.v1.RequestUsageEvent) into Connect records for
// the Iceberg sink. Shipped as one shaded jar in the Connect image (infra/connect).
plugins { id("billing.connect-plugin") }

dependencies {
    implementation(project(":libs:contracts"))
    compileOnly(libs.kafka.connect.api) // provided by the Connect worker
    testImplementation(libs.kafka.connect.api)
}

tasks.shadowJar {
    archiveClassifier = "plugin"
    // Connect gives each plugin directory its own classloader; keep protobuf private to it.
    relocate("com.google.protobuf", "com.logicvoid0x.billing.usageconnect.shaded.protobuf")
}

// Copies the plugin into the Connect image's build context (gitignored), see infra/connect.
val stageConnectPlugin by tasks.registering(Copy::class) {
    from(tasks.shadowJar)
    into(rootProject.layout.projectDirectory.dir("infra/connect/plugins/usage-connect"))
}
