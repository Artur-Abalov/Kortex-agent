plugins {
    kotlin("jvm") version "1.9.22"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("com.google.protobuf") version "0.9.4"
}

group = "io.kortex"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // ByteBuddy for instrumentation
    implementation("net.bytebuddy:byte-buddy:1.14.11")
    
    // gRPC dependencies
    implementation("io.grpc:grpc-protobuf:1.60.1")
    implementation("io.grpc:grpc-stub:1.60.1")
    implementation("io.grpc:grpc-netty:1.60.1")
    implementation("com.google.protobuf:protobuf-java:3.25.5")
    
    // javax.annotation for generated code
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")
    
    // Kotlin standard library
    implementation(kotlin("stdlib"))
    
    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    jvmToolchain(11)
}

tasks {
    shadowJar {
        archiveClassifier.set("")
        mergeServiceFiles()
        
        manifest {
            attributes(
                "Premain-Class" to "io.kortex.agent.KortexAgent",
                "Can-Retransform-Classes" to "true",
                "Can-Redefine-Classes" to "true"
            )
        }
    }
    
    build {
        dependsOn(shadowJar)
    }
    
    test {
        useJUnitPlatform()
    }
    
    processResources {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.60.1"
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
        proto {
            srcDir("src/main/proto")
        }
    }
}
