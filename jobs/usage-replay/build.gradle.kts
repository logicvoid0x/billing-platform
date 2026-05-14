plugins { id("billing.flink-job") }

tasks.jar { manifest { attributes("Main-Class" to "com.logicvoid0x.billing.usagereplay.UsageReplayJob") } }
