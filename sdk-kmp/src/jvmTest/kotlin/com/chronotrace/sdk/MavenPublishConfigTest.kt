package com.chronotrace.sdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Validates Maven publish configuration for the sdk-kmp KMP module.
 * Fails until the maven-publish plugin is correctly configured with
 * groupId=com.chronotrace, artifactId=sdk-kmp-<target>, and
 * version=0.1.0-SNAPSHOT for JVM/JS/Wasm targets.
 */
class MavenPublishConfigTest {

    private val pomDir = File(
        "/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/build/publications"
    )

    @Test
    fun `jvm POM has correct groupId artifactId and version`() {
        val pom = File(pomDir, "jvm/pom-default.xml")
        assertTrue(pom.exists(), "jvm POM not generated — run :sdk-kmp:generatePomFileForJvmPublication")

        val content = pom.readText()
        assertTrue(content.contains("<groupId>com.chronotrace</groupId>"), "groupId should be com.chronotrace")
        assertTrue(content.contains("<artifactId>sdk-kmp-jvm</artifactId>"), "artifactId should be sdk-kmp-jvm")
        assertTrue(content.contains("<version>0.1.0-SNAPSHOT</version>"), "version should be 0.1.0-SNAPSHOT")
    }

    @Test
    fun `js POM has correct groupId artifactId and version`() {
        val pom = File(pomDir, "js/pom-default.xml")
        assertTrue(pom.exists(), "js POM not generated — run :sdk-kmp:generatePomFileForJsPublication")

        val content = pom.readText()
        assertTrue(content.contains("<groupId>com.chronotrace</groupId>"), "groupId should be com.chronotrace")
        assertTrue(content.contains("<artifactId>sdk-kmp-js</artifactId>"), "artifactId should be sdk-kmp-js")
        assertTrue(content.contains("<version>0.1.0-SNAPSHOT</version>"), "version should be 0.1.0-SNAPSHOT")
    }

    @Test
    fun `wasmJs POM has correct groupId artifactId and version`() {
        val pom = File(pomDir, "wasmJs/pom-default.xml")
        assertTrue(pom.exists(), "wasmJs POM not generated — run :sdk-kmp:generatePomFileForWasmJsPublication")

        val content = pom.readText()
        assertTrue(content.contains("<groupId>com.chronotrace</groupId>"), "groupId should be com.chronotrace")
        assertTrue(content.contains("<artifactId>sdk-kmp-wasm</artifactId>"), "artifactId should be sdk-kmp-wasm")
        assertTrue(content.contains("<version>0.1.0-SNAPSHOT</version>"), "version should be 0.1.0-SNAPSHOT")
    }
}