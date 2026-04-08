plugins {
    kotlin("jvm")
    `java-gradle-plugin`
}

dependencies {
    implementation(gradleApi())
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.2.21")
    implementation(project(":chronotrace-kotlin-plugin"))
}

gradlePlugin {
    plugins {
        create("chronoTraceKotlinPlugin") {
            id = "org.chronotrace.kotlin-plugin"
            implementationClass = "org.chronotrace.gradle.ChronoTraceKotlinPlugin"
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
