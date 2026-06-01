plugins {
    kotlin("jvm") version "2.2.21"
}

group = "org.chronotrace"
version = rootProject.version

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.21")
    compileOnly(project(":chronotrace-contract"))

    // Test dependencies
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.21")
    testImplementation(project(":chronotrace-contract"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.12.2")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.12.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.12.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.2.21")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    testImplementation("io.ktor:ktor-server-netty-jvm:3.1.1")
    testImplementation("io.ktor:ktor-server-core-jvm:3.1.1")
    testImplementation("io.ktor:ktor-server-content-negotiation-jvm:3.1.1")
    testImplementation("io.ktor:ktor-serialization-kotlinx-json-jvm:3.1.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(gradleTestKit())
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes["Kotlin-Compiler-Plugin-Class"] = "org.chronotrace.plugin.wasm.ChronoTraceWasmCompilerPluginRegistrar"
    }
}