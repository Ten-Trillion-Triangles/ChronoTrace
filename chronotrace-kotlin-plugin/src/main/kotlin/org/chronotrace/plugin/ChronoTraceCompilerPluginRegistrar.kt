package org.chronotrace.plugin

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration

@OptIn(ExperimentalCompilerApi::class)
class ChronoTraceCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val supportsK2: Boolean = true

    override fun CompilerPluginRegistrar.ExtensionStorage.registerExtensions(
        configuration: CompilerConfiguration,
    ) {
        // Default to Int.MAX_VALUE (capture all visible locals) — matches
        // JS/Wasm variants and the shared IrCoreConfiguration default.
        // The legacy value of 3 is preserved for users who explicitly
        // opt in via the Gradle plugin's captureDepth property.
        val captureDepth = configuration.get(ChronoTraceConfigurationKeys.CAPTURE_DEPTH, Int.MAX_VALUE)
        val redactionList = configuration.get(ChronoTraceConfigurationKeys.REDACTION_LIST, "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val irConfig = ChronoTraceIrGenerationExtension.Configuration(
            captureDepth = captureDepth,
            redactionList = redactionList,
        )
        IrGenerationExtension.registerExtension(ChronoTraceIrGenerationExtension(irConfig))
    }
}
