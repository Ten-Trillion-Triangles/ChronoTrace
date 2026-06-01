package org.chronotrace.plugin

import org.jetbrains.kotlin.config.CompilerConfigurationKey

/**
 * Compiler configuration keys for ChronoTrace plugin options.
 *
 * The values are read in [ChronoTraceCompilerPluginRegistrar.registerExtensions] and
 * used to construct the [ChronoTraceIrGenerationExtension.Configuration] that drives
 * capture depth and local-variable redaction.
 */
object ChronoTraceConfigurationKeys {
    val CAPTURE_DEPTH: CompilerConfigurationKey<Int> =
        CompilerConfigurationKey.create("chronotrace.capture.depth")

    val REDACTION_LIST: CompilerConfigurationKey<String> =
        CompilerConfigurationKey.create("chronotrace.redaction.list")
}
