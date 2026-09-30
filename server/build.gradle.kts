plugins {
    kotlin("jvm")
    application
}

val grpcVersion = "1.69.0"
val grpcKotlinVersion = "1.4.1"

dependencies {
    implementation(project(":api"))

    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    // gRPC Netty Shaded for TLS / OpenSSL mTLS support
    implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("io.grpc:grpc-kotlin-stub:$grpcKotlinVersion")

    // Jackson for policy JSON parsing
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.16")
}

application {
    mainClass.set("com.example.server.ServerMainKt")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}
