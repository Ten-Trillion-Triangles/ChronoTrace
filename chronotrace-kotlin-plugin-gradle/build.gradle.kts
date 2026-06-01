plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    `maven-publish`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(gradleApi())
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.2.21")
    testImplementation(gradleTestKit())
    testImplementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21")
    testImplementation(kotlin("test"))
}

gradlePlugin {
    plugins {
        create("chronoTraceKotlinPlugin") {
            id = "org.chronotrace.kotlin-plugin"
            implementationClass = "org.chronotrace.gradle.ChronoTraceKotlinPlugin"
        }
    }
}

group = "com.chronotrace"
version = rootProject.version

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifactId = "chronotrace-kotlin-plugin-gradle"
    }
}

// Convenience task so the artifact can be consumed locally for smoke tests
// (e.g. `./gradlew :chronotrace-kotlin-plugin-gradle:publishToMavenLocal`).
tasks.register("verifyPublish") {
    group = "publishing"
    description = "Builds the plugin JAR and verifies maven-publish metadata is generated."
    dependsOn("generatePomFileForMavenPublication", "jar")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
