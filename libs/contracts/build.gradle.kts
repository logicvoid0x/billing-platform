plugins {
    id("billing.java-library-17")
    id("billing.protobuf")
}

dependencies {
    api(project(":libs:domain")) // codecs between wire messages and domain records
    api(project(":libs:rating")) // catalog snapshot codec and client (Q7)
    testImplementation(libs.grpc.inprocess)
    testImplementation(testFixtures(project(":libs:rating")))
}
