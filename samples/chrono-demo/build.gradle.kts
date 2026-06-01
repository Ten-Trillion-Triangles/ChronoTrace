plugins {
    kotlin("jvm")
    id("org.chronotrace.kotlin-plugin")
    application
}

group = "com.chronotrace.samples"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("demo.DemoApplicationKt")
}

// SDK JAR - the build already sets chronotrace.plugin.jar via gradle.properties
val workspaceRoot = file(rootProject.projectDir.absolutePath + "/../..")
val sdkJar = file("$workspaceRoot/sdk-kmp/build/libs/sdk-kmp-jvm-1.0.0.jar")

println("ChronoTrace: SDK JAR at ${sdkJar.absolutePath} (exists: ${sdkJar.exists()})")

dependencies {
    implementation(files(sdkJar.absolutePath))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}