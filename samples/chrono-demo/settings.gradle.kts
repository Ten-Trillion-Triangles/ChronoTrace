pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        kotlin("jvm") version "2.2.21"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "chrono-demo"

includeBuild("../../chronotrace-kotlin-plugin-gradle")

includeBuild("../../chronotrace-kotlin-plugin")