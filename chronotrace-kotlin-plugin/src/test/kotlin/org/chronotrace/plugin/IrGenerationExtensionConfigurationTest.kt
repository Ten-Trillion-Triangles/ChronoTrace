package org.chronotrace.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [ChronoTraceIrGenerationExtension.Configuration].
 *
 * The compiler-driven [ChronoTraceCompilerPluginRegistrar] reads plugin options
 * from [org.jetbrains.kotlin.config.CompilerConfiguration] and feeds them into
 * a [ChronoTraceIrGenerationExtension.Configuration] data class. The test here
 * pins the data class contract that the rewriter depends on:
 *   * `redactionList` is parsed into regexes on demand and `shouldRedact`
 *     returns `true` only for names that match one of the configured patterns.
 *   * `captureDepth` is honoured: `take(captureDepth)` is applied when
 *     filtering visible locals.
 */
class IrGenerationExtensionConfigurationTest {

    @Test
    fun `default configuration redacts nothing`() {
        val cfg = ChronoTraceIrGenerationExtension.Configuration()
        assertEquals(3, cfg.captureDepth, "default capture depth is 3")
        assertTrue(cfg.redactionList.isEmpty())
        assertFalse(cfg.shouldRedact("password"))
        assertFalse(cfg.shouldRedact("anything"))
    }

    @Test
    fun `shouldRedact returns true when name matches a configured pattern`() {
        val cfg = ChronoTraceIrGenerationExtension.Configuration(
            redactionList = listOf("^password$", "^apiKey$"),
        )
        assertTrue(cfg.shouldRedact("password"))
        assertTrue(cfg.shouldRedact("apiKey"))
        assertFalse(cfg.shouldRedact("username"))
    }

    @Test
    fun `redaction patterns are interpreted as regex`() {
        val cfg = ChronoTraceIrGenerationExtension.Configuration(
            redactionList = listOf(".*[Ss]ecret.*"),
        )
        assertTrue(cfg.shouldRedact("clientSecret"))
        assertTrue(cfg.shouldRedact("my_secret_value"))
        assertFalse(cfg.shouldRedact("name"))
    }

    @Test
    fun `captureDepth is exposed verbatim`() {
        val cfg = ChronoTraceIrGenerationExtension.Configuration(captureDepth = 7)
        assertEquals(7, cfg.captureDepth)
    }
}
