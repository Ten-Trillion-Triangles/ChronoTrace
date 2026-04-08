plugins {
    kotlin("jvm") version "2.2.21" apply false
    kotlin("plugin.serialization") version "2.2.21" apply false
    kotlin("multiplatform") version "2.2.21" apply false
    id("io.ktor.plugin") version "3.1.1" apply false
}

allprojects {
    group = "org.chronotrace"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}

