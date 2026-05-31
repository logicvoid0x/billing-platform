plugins {
    id("billing.java-library-17")
    id("billing.protobuf")
}

dependencies {
    testImplementation(libs.grpc.inprocess)
}
