plugins {
    kotlin("jvm") version "2.2.21" apply false
    kotlin("plugin.serialization") version "2.2.21" apply false
    kotlin("multiplatform") version "2.2.21" apply false
    id("io.ktor.plugin") version "3.1.1" apply false
}

// Single source of truth for ChronoTrace version.
// Set via gradle.properties CHRONOTRACE_VERSION; do not hardcode in subprojects.
val chronoTraceVersion: String = providers.gradleProperty("CHRONOTRACE_VERSION").get()

allprojects {
    group = "com.chronotrace"
    version = chronoTraceVersion

    repositories {
        mavenCentral()
    }
}

