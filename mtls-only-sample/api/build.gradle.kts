plugins {
    `java-library`
    kotlin("jvm")
    id("com.google.protobuf")
}

val grpcVersion = "1.69.0"
val grpcKotlinVersion = "1.4.1"
val protobufVersion = "4.29.3"

dependencies {
    api("org.jetbrains.kotlin:kotlin-stdlib")
    api("javax.annotation:javax.annotation-api:1.3.2")

    api("com.google.protobuf:protobuf-kotlin:$protobufVersion")
    api("com.google.protobuf:protobuf-java:$protobufVersion")
    api("io.grpc:grpc-stub:$grpcVersion")
    api("io.grpc:grpc-protobuf:$grpcVersion")
    api("io.grpc:grpc-kotlin-stub:$grpcKotlinVersion")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
        create("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:$grpcKotlinVersion:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
                create("grpckt")
            }
            task.builtins {
                create("kotlin")
            }
        }
    }
}

sourceSets {
    main {
        java {
            srcDir("build/generated/source/proto/main/java")
            srcDir("build/generated/source/proto/main/grpc")
            srcDir("build/generated/source/proto/main/grpckt")
            srcDir("build/generated/source/proto/main/kotlin")
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
