plugins {
    kotlin("jvm")
    application
}

val akkaVersion = "2.6.20"
val akkaHttpVersion = "10.2.10"
val grpcVersion = "1.69.0"

dependencies {
    implementation(project(":api"))

    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    // Akka Streams and Akka HTTP
    implementation("com.typesafe.akka:akka-stream_2.13:$akkaVersion")
    implementation("com.typesafe.akka:akka-http_2.13:$akkaHttpVersion")
    implementation("com.typesafe.akka:akka-actor-typed_2.13:$akkaVersion")

    // gRPC client runtime
    implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.16")
}

application {
    mainClass.set("com.example.client.ClientMainKt")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}
