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
    // SDK JAR is resolved at runtime via the SDK's pre-built JAR on the classpath.
    // The plugin references SDK symbols (ChronoLogger, withTraceCaptured, etc.)
    // through the published SDK JAR, not the SDK source.
    testImplementation(project(":chronotrace-server"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.2.21")
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
        attributes["Kotlin-Compiler-Plugin-Class"] = "org.chronotrace.plugin.js.ChronoTraceJsCompilerPluginRegistrar"
    }
}