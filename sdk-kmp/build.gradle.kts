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
                implementation("io.ktor:ktor-client-core:2.3.12")
                implementation("io.ktor:ktor-client-okhttp:2.3.12")
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
        named("jsTest") {
            dependencies {
                implementation(kotlin("test-js"))
            }
        }
        named("wasmJsTest") {
            dependencies {
                implementation(kotlin("test-wasm-js"))
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

val chronoTraceCompilerPluginJar = rootProject.project(":chronotrace-kotlin-plugin").tasks.named("jar", Jar::class.java)

tasks.withType(KotlinCompilationTask::class.java).configureEach {
    dependsOn(chronoTraceCompilerPluginJar)
    compilerOptions.freeCompilerArgs.addAll(
        project.provider {
            listOf(
                "-Xplugin=${chronoTraceCompilerPluginJar.get().archiveFile.get().asFile.absolutePath}",
                "-Xexpect-actual-classes",
            )
        },
    )
}
