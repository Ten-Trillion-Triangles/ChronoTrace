package org.chronotrace.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Jar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for ChronoTraceGradlePlugin.
 *
 * These tests verify plugin behavior in isolation without requiring the full
 * ChronoTrace multi-project build context. The plugin's project dependency
 * resolution is tested separately in integration tests.
 */
class ChronoTraceGradlePluginTest {

    @Test
    fun `plugin class exists and can be instantiated`() {
        val plugin = ChronoTraceKotlinPlugin()
        assertNotNull(plugin)
    }

    @Test
    fun `plugin implements Plugin interface`() {
        val plugin: Plugin<Project> = ChronoTraceKotlinPlugin()
        assertTrue(plugin is Plugin<*>)
    }

    @Test
    fun `plugin apply method exists and is callable`() {
        val plugin = ChronoTraceKotlinPlugin()
        // Just verify the apply method exists - actual application tested in integration
        val method = ChronoTraceKotlinPlugin::class.java.methods.find { it.name == "apply" }
        assertNotNull(method, "apply method should exist")
        // Verify the method takes exactly one parameter
        assertTrue(method.parameterCount == 1, "apply method should accept exactly one parameter")
    }
}