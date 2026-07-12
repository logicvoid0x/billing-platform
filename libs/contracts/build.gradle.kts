plugins {
    id("billing.java-library-17")
    id("billing.protobuf")
}

dependencies {
    api(project(":libs:domain")) // codecs between wire messages and domain records
    testImplementation(libs.grpc.inprocess)
}
