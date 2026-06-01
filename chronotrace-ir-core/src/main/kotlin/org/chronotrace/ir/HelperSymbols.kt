@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
@file:OptIn(
    org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi::class,
    org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class,
    org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI::class,
)

package org.chronotrace.ir

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Resolves the SDK symbols ([mergeCaptureFields], [withTraceCaptured],
 * [withSpanCaptured]) and stdlib helpers ([emptyMap], [mapOf],
 * [pairClass]) that the IR rewriter uses to construct the
 * captured-locals map argument.
 *
 * Constructor throws [IllegalStateException] if any required symbol is
 * not on the compiler classpath; the JVM variant catches this and
 * skips instrumentation (graceful degradation), while the JS and Wasm
 * variants propagate so the build fails loudly.
 */
internal class HelperSymbols(
    pluginContext: IrPluginContext,
) {
    private val chronoPackage = FqName(CHRONO_PACKAGE)
    private val kotlinCollections = FqName("kotlin.collections")

    val mergeCaptureFields: IrFunctionSymbol = pluginContext.referenceFunctions(
        CallableId(chronoPackage, Name.identifier("mergeCaptureFields")),
    ).firstOrNull()
        ?: error(
            "Cannot resolve required SDK symbol 'mergeCaptureFields'. " +
                "The ChronoTrace SDK must be on the Kotlin compiler classpath.",
        )

    val withTraceCaptured: IrFunctionSymbol = pluginContext.referenceFunctions(
        CallableId(chronoPackage, Name.identifier("withTraceCaptured")),
    ).firstOrNull()
        ?: error(
            "Cannot resolve required SDK symbol 'withTraceCaptured'. " +
                "The ChronoTrace SDK must be on the Kotlin compiler classpath.",
        )

    val withSpanCaptured: IrFunctionSymbol = pluginContext.referenceFunctions(
        CallableId(chronoPackage, Name.identifier("withSpanCaptured")),
    ).firstOrNull()
        ?: error(
            "Cannot resolve required SDK symbol 'withSpanCaptured'. " +
                "The ChronoTrace SDK must be on the Kotlin compiler classpath.",
        )

    val emptyMap: IrFunctionSymbol = pluginContext.referenceFunctions(
        CallableId(kotlinCollections, Name.identifier("emptyMap")),
    ).firstOrNull()
        ?: error(
            "Cannot resolve required SDK symbol 'kotlin.collections.emptyMap'. " +
                "The Kotlin stdlib must be on the compiler classpath.",
        )

    val mapOf: IrFunctionSymbol = pluginContext.referenceFunctions(
        CallableId(kotlinCollections, Name.identifier("mapOf")),
    ).firstOrNull { it.owner.valueParameters.singleOrNull()?.varargElementType != null }
        ?: error(
            "Cannot resolve required SDK symbol 'kotlin.collections.mapOf' (vararg overload). " +
                "The Kotlin stdlib must be on the compiler classpath.",
        )

    val pairClass: IrClassSymbol = pluginContext.referenceClass(ClassId.topLevel(FqName("kotlin.Pair")))
        ?: error(
            "Cannot resolve required SDK symbol 'kotlin.Pair'. " +
                "The Kotlin stdlib must be on the compiler classpath.",
        )
}
