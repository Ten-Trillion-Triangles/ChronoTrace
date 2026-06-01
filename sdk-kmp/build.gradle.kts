import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JsModuleKind
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import org.gradle.api.tasks.bundling.Jar
import org.gradle.plugin.use.PluginDependencySpec

plugins {
    kotlin("multiplatform")
    `maven-publish`
}

@OptIn(ExperimentalWasmDsl::class)
kotlin {
    jvm()
    js(IR) {
        browser()
        nodejs()
    }
    wasmJs {
        browser()
        nodejs()
    }
    linuxX64()
    macosX64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":chronotrace-contract"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        named("jvmMain") {
            dependencies {
                implementation("io.ktor:ktor-client-core:3.1.1")
                implementation("io.ktor:ktor-client-okhttp:3.1.1")
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
            }
        }
        named("jvmTest") {
            dependencies {
                implementation(kotlin("test-junit5"))
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
                implementation("com.squareup.okhttp3:mockwebserver:4.12.0")
            }
        }
        named("jsMain") {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
                implementation("io.ktor:ktor-client-core:3.1.1")
                implementation("io.ktor:ktor-client-js:3.1.1")
            }
        }
        named("jsTest") {
            dependencies {
                implementation(kotlin("test-js"))
            }
        }
        named("wasmJsMain") {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
                implementation("io.ktor:ktor-client-core:3.1.1")
                implementation("io.ktor:ktor-client-js:3.1.1")
            }
        }
        named("wasmJsTest") {
            dependencies {
                implementation(kotlin("test-wasm-js"))
            }
        }
        named("linuxX64Main") {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
                implementation("io.ktor:ktor-client-core:3.1.1")
                implementation("io.ktor:ktor-client-cio:3.1.1")
            }
        }
        named("macosX64Main") {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
                implementation("io.ktor:ktor-client-core:3.1.1")
                implementation("io.ktor:ktor-client-cio:3.1.1")
            }
        }
        named("linuxX64Test") {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            }
        }
        named("macosX64Test") {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            }
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifactId = when (name) {
            "jvm" -> "sdk-kmp-jvm"
            "js" -> "sdk-kmp-js"
            "wasmJs" -> "sdk-kmp-wasm"
            "linuxX64" -> "sdk-kmp-linux-x64"
            "macosX64" -> "sdk-kmp-macos-x64"
            else -> artifactId
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Ensure POM files are generated before tests run
    tasks.findByName("generatePomFileForJvmPublication")?.let { dependsOn(it) }
    tasks.findByName("generatePomFileForJsPublication")?.let { dependsOn(it) }
    tasks.findByName("generatePomFileForWasmJsPublication")?.let { dependsOn(it) }
}

tasks.withType(KotlinCompilationTask::class.java).configureEach {
    doFirst {
        try {
            val taskName = name
            // Skip Native compileKotlin tasks: the JVM K2 IR plugin JAR cannot instrument
            // Native targets (Kotlin/Native has its own IR pipeline). Injecting it would
            // add `-Xplugin=...` flags the Native compiler silently ignores, and it makes
            // the build slower.
            if (taskName.contains("linuxX64") || taskName.contains("macosX64")) {
                return@doFirst
            }
            val targetProjectName = when {
                taskName.contains("wasmJs") -> ":chronotrace-kotlin-plugin-wasm"
                taskName.contains("Js") && !taskName.contains("WasmJs") -> ":chronotrace-kotlin-plugin-js"
                else -> ":chronotrace-kotlin-plugin"
            }
            val pluginProject = project.rootProject.project(targetProjectName)
            val pluginJar = pluginProject.tasks.named("jar", Jar::class.java).get().archiveFile.get().asFile
            if (pluginJar.exists()) {
                dependsOn(pluginProject.tasks.named("jar"))
                compilerOptions.freeCompilerArgs.addAll(
                    listOf(
                        "-Xplugin=${pluginJar.absolutePath}",
                        "-Xexpect-actual-classes",
                        "-Xsuppress-warning=DEPRECATION",
                    )
                )
            } else {
                logger.warn("ChronoTrace plugin JAR not found at ${pluginJar.absolutePath}. Build continues without instrumentation.")
            }
        } catch (e: Exception) {
            logger.warn("ChronoTrace plugin failed to load: ${e.message}. Build continues without instrumentation.")
        }
    }
}
