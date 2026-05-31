// Protobuf + gRPC code generation (libs:contracts). Apply after a java-library convention plugin.
import com.google.protobuf.gradle.id

plugins { id("com.google.protobuf") }

val libs = the<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()

dependencies {
    "api"(platform(lib("grpc-bom")))
    "api"(lib("protobuf-java"))
    "api"(lib("grpc-protobuf"))
    "api"(lib("grpc-stub"))
}

protobuf {
    protoc { artifact = lib("protoc").get().toString() }
    plugins { id("grpc") { artifact = lib("grpc-protoc-plugin").get().toString() } }
    generateProtoTasks {
        all().configureEach { plugins { id("grpc") {} } }
    }
}

// Generated code is not ours to lint; hand-written sources elsewhere keep -Xlint:all -Werror.
tasks.named<JavaCompile>("compileJava") {
    options.compilerArgs.removeAll { it == "-Werror" || it == "-Xlint:all" }
    options.compilerArgs.add("-Xlint:none")
}
