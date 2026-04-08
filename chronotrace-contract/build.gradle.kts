import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
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
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

val generatedTsContracts = rootProject.layout.projectDirectory.file("sdk-ts/src/generated/contracts.ts")

val jvmMainCompilation = kotlin.targets.getByName("jvm").compilations.getByName("main")

tasks.register<JavaExec>("generateTypeScriptContracts") {
    group = "codegen"
    description = "Generates TypeScript contract types from the canonical Kotlin contracts."
    dependsOn(jvmMainCompilation.compileTaskProvider)
    mainClass.set("org.chronotrace.contract.TypeScriptContractGeneratorKt")
    classpath = files(jvmMainCompilation.output.allOutputs, jvmMainCompilation.runtimeDependencyFiles)
    args(generatedTsContracts.asFile.absolutePath)
}

tasks.register<JavaExec>("verifyTypeScriptContracts") {
    group = "verification"
    description = "Fails when the generated TypeScript contract file is stale."
    dependsOn(jvmMainCompilation.compileTaskProvider)
    mainClass.set("org.chronotrace.contract.TypeScriptContractGeneratorKt")
    classpath = files(jvmMainCompilation.output.allOutputs, jvmMainCompilation.runtimeDependencyFiles)
    args("--check", generatedTsContracts.asFile.absolutePath)
}
