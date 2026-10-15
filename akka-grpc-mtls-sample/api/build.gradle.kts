plugins {
    `java-library`
    kotlin("jvm")
    id("com.google.protobuf")
}

val protobufVersion = "3.25.5"
val grpcVersion = "1.69.0"
val akkaVersion = "2.6.20"
val akkaHttpVersion = "10.2.10"

dependencies {
    api("org.jetbrains.kotlin:kotlin-stdlib")
    api("javax.annotation:javax.annotation-api:1.3.2")

    // Protobuf Java & gRPC Java Stub
    api("com.google.protobuf:protobuf-java:$protobufVersion")
    api("io.grpc:grpc-stub:$grpcVersion")
    api("io.grpc:grpc-protobuf:$grpcVersion")

    // Akka Streams and Akka HTTP for Akka gRPC
    api("com.typesafe.akka:akka-stream_2.13:$akkaVersion")
    api("com.typesafe.akka:akka-http_2.13:$akkaHttpVersion")
    api("com.typesafe.akka:akka-actor-typed_2.13:$akkaVersion")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
            }
        }
    }
}

sourceSets {
    main {
        java {
            srcDir("build/generated/source/proto/main/java")
            srcDir("build/generated/source/proto/main/grpc")
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn("generateProto")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}
