pluginManagement { includeBuild("build-logic") }

plugins {
    // Auto-provisions the Java 17 and 21 toolchains.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "billing-platform"

include(
    "libs:money", "libs:domain", "libs:contracts", "libs:rating", "libs:admission", "libs:testing",
    "libs:usage-connect",
    "services:catalog", "services:budget-shard", "services:balance-ledger", "services:gateway",
    "services:inference-sim", "services:rating-engine", "services:reconciliation",
    "services:invoicing", "services:payments", "services:usage-view",
    "jobs:usage-aggregator", "jobs:usage-replay",
)
