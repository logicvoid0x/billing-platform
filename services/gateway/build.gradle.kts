plugins { id("billing.java-service-21") }

dependencies {
    implementation(project(":libs:admission"))
    implementation(project(":libs:contracts"))
    implementation(libs.kafka.clients)
    implementation(libs.grpc.netty)
    implementation(libs.spring.boot.starter.webmvc) // versions from the Spring Boot BOM
    testImplementation(libs.spring.boot.starter.test)

    testImplementation(project(":services:budget-shard")) // the real shard service, in process
    testImplementation(libs.grpc.inprocess)

    testImplementation(testFixtures(project(":libs:money")))
    testImplementation(testFixtures(project(":libs:rating")))
}
