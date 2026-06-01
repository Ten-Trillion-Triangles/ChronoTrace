// chronotrace-ir-core — shared IR transformation logic for all 3 K2 compiler plugins
// (JVM, JS, WasmJs). Holds HelperSymbols, the visitor base class, the
// findInnerSuspendLambdaFunction heuristic, and the classStats counter.
//
// Each plugin module is a thin wrapper that just wires the platform-specific
// symbol resolver and provides the right `classId` for cross-target FQ-names.

plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // The K2 compiler API. The K2 plugin pipeline lives in the
    // org.jetbrains.kotlin:kotlin-compiler-embeddable artifact. We pin to the
    // same version the consuming Gradle build will use.
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.21")

    // For tests
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.test {
    useJUnitPlatform()
}

// The K2 compiler uses some sun.* internals via the embeddable JAR; this is OK
// because the JAR is repackaged. We need to add these JVM args for tests.
tasks.withType<Test>().configureEach {
    jvmArgs(
        "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
    )
}
