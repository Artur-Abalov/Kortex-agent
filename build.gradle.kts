plugins {
    kotlin("jvm") version "1.9.22"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("com.google.protobuf") version "0.9.4"
    id("io.gitlab.arturbosch.detekt") version "1.23.5"
    id("org.owasp.dependencycheck") version "9.0.9"
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
    testImplementation("com.h2database:h2:2.2.224")
    testImplementation("javax.annotation:javax.annotation-api:1.3.2")
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
        dependsOn(shadowJar)
        systemProperty("agent.jar.path", shadowJar.get().archiveFile.get().asFile.absolutePath)
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

// ── Detekt static analysis configuration ─────────────────────────────────

detekt {
    config.setFrom(files("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    parallel = true
    baseline = file("detekt-baseline.xml")
    // Analyse main sources only (exclude generated protobuf code)
    source.setFrom(files("src/main/kotlin", "src/test/kotlin"))
}

// ── OWASP Dependency-Check configuration ─────────────────────────────────

dependencyCheck {
    // Fail the build when a CVE with CVSS ≥ 7.0 is found
    failBuildOnCVSS = 7.0f
    // Scan all configurations
    scanConfigurations = listOf("runtimeClasspath", "compileClasspath")
    // Suppress false positives via suppression file if needed
    suppressionFiles = listOf("config/owasp-suppressions.xml")
    analyzers.assemblyEnabled = false
    // Use NVD API key when available (highly recommended for reliable NVD access)
    nvd.apiKey = System.getenv("NVD_API_KEY") ?: ""
}

// ── License compliance check ─────────────────────────────────────────────

tasks.register("checkLicenses") {
    group = "verification"
    description = "Verify that all dependencies use licenses compatible with MPL 2.0"

    doLast {
        val allowed = setOf(
            "Apache License, Version 2.0",
            "The Apache License, Version 2.0",
            "The Apache Software License, Version 2.0",
            "Apache-2.0",
            "Apache 2.0",
            "MIT License",
            "MIT",
            "The MIT License",
            "BSD License",
            "BSD",
            "The BSD License",
            "BSD-2-Clause",
            "BSD-3-Clause",
            "BSD 3-Clause",
            "New BSD License",
            "Revised BSD License",
            "Mozilla Public License, Version 2.0",
            "MPL 2.0",
            "MPL-2.0",
            "Eclipse Public License 1.0",
            "Eclipse Public License - v 1.0",
            "Eclipse Public License - v 2.0",
            "EPL-1.0",
            "EPL-2.0",
            "The 2-Clause BSD License",
            "The 3-Clause BSD License",
            "ISC License",
            "ISC",
            "Public Domain",
            "CC0",
            "Unlicense"
        )

        val violations = mutableListOf<String>()
        configurations.getByName("runtimeClasspath").resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
            val id = artifact.moduleVersion.id
            val pom = artifact.file.parentFile.resolve(
                "${id.name}-${id.version}.pom"
            )
            if (pom.exists()) {
                val pomText = pom.readText()
                val licenseMatch = Regex("<license>.*?<name>(.*?)</name>.*?</license>", RegexOption.DOT_MATCHES_ALL)
                    .find(pomText)
                val licenseName = licenseMatch?.groupValues?.get(1)?.trim()
                if (licenseName != null && allowed.none { licenseName.contains(it, ignoreCase = true) }) {
                    violations.add("${id.group}:${id.name}:${id.version} → $licenseName")
                }
            }
        }
        if (violations.isNotEmpty()) {
            logger.warn("Dependencies with potentially incompatible licenses:")
            violations.forEach { logger.warn("  - $it") }
        } else {
            logger.lifecycle("All resolved dependencies have MPL 2.0–compatible licenses (or no license metadata found).")
        }
    }
}
