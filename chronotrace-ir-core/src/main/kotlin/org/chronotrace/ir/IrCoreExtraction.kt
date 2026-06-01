@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
@file:OptIn(
    org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi::class,
    org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class,
    org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI::class,
)

package org.chronotrace.ir

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrComposite
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

/**
 * The ChronoTrace SDK package whose top-level helper functions and the
 * `ChronoLogger` object live in. Hard-coded because every plugin
 * variant targets the same SDK published from `:sdk-kmp`.
 */
internal const val CHRONO_PACKAGE = "com.chronotrace.sdk"

/**
 * The names of the methods on `com.chronotrace.sdk.ChronoLogger` that
 * the rewriter recognises as logger calls. The rewriter merges the
 * caller's visible locals into the existing `fields` map argument.
 */
private val LOGGER_METHODS = setOf("trace", "debug", "info", "warn", "error", "fatal")

private val IrDeclaration.parentClassName: String?
    get() = (this as? IrFunction)?.parent?.let { parent ->
        when (parent) {
            is IrClass -> parent.name.asString()
            else -> null
        }
    }

/**
 * Apply the ChronoTrace IR rewriter to [this] module fragment.
 *
 * This is the single entry point shared by all three plugin variants
 * (JVM, JS, Wasm). The visitor walks the entire module looking for
 * `ChronoLogger.trace/debug/info/warn/error/fatal` calls and
 * `com.chronotrace.sdk.withTrace` / `com.chronotrace.sdk.withSpan`
 * calls; when it finds one it inserts a captured-locals map as a
 * synthetic argument so the SDK can record local-variable values in
 * frame snapshots.
 *
 * @param pluginContext The Kotlin compiler's IR plugin context for
 *                      symbol resolution and builder construction.
 * @param pluginLabel   Per-platform log prefix written to stdout
 *                      (the Kotlin daemon log). Tests pattern-match
 *                      against `"$pluginLabel: \\d+ functions
 *                      instrumented, \\d+ locals captured"`.
 *                      Use `"ChronoTrace"` for JVM, `"ChronoTrace[JS]"`
 *                      for JS, `"ChronoTrace[Wasm]"` for Wasm.
 * @param configuration Capture-depth and redaction settings. Default
 *                      keeps all visible locals (no filtering).
 *
 * @throws IllegalStateException if any required SDK or stdlib symbol
 *                               is not on the compiler classpath.
 *                               The JVM variant wraps this in a
 *                               try/catch for graceful degradation;
 *                               JS and Wasm do not, so a missing SDK
 *                               will fail the build.
 */
fun IrModuleFragment.applyChronoTraceIr(
    pluginContext: IrPluginContext,
    pluginLabel: String,
    configuration: IrCoreConfiguration = IrCoreConfiguration(),
) {
    val helperSymbols = HelperSymbols(pluginContext)
    val stats = InstrumentationStats()

    transformChildrenVoid(
        ChronoTraceIrVisitor(
            pluginContext = pluginContext,
            helperSymbols = helperSymbols,
            configuration = configuration,
            stats = stats,
        ),
    )

    val instrumentedFns = stats.classStats.values.sumOf { it.size }
    val capturedLocals = stats.totalCallsRewritten
    println("$pluginLabel: $instrumentedFns functions instrumented, $capturedLocals locals captured")
    stats.classStats.entries.take(5).forEach { (cls, fns) ->
        println("$pluginLabel:   $cls → ${fns.size} fn(s) instrumented [${fns.joinToString(", ")}]")
    }
}

/**
 * Mutable instrumentation tally that the visitor updates as it walks
 * the IR. Held outside the visitor so the entry-point function can
 * read it back after `transformChildrenVoid` returns.
 */
private class InstrumentationStats {
    var totalFunctionsVisited: Int = 0
    var totalCallsRewritten: Int = 0
    /**
     * className -> distinct function FQ-names whose body had at least
     * one call rewritten. A `Set` so the same function containing
     * several ChronoLogger calls is counted once.
     */
    val classStats: MutableMap<String, MutableSet<String>> = mutableMapOf()
    var currentClassName: String? = null
    var currentFunctionFqName: String? = null
}

/**
 * The shared IR visitor. K2's IR generation phase runs *before* the
 * suspend state-machine lowering, so a `suspend fun foo` body is still
 * a regular `IrBlock` at this point — the standard
 * [IrElementTransformerVoid] recursion via
 * `declaration.transformChildrenVoid(this)` reaches `ChronoLogger.*`
 * calls inside suspend bodies without any special "descend into inner
 * `SuspendLambda`" step.
 *
 * This visitor is identical across JVM, JS and Wasm.
 */
private class ChronoTraceIrVisitor(
    private val pluginContext: IrPluginContext,
    private val helperSymbols: HelperSymbols,
    private val configuration: IrCoreConfiguration,
    private val stats: InstrumentationStats,
) : IrElementTransformerVoid() {

    private val scopeStack = ArrayDeque<LinkedHashMap<String, IrValueDeclaration>>()

    override fun visitFunction(declaration: IrFunction): IrStatement {
        stats.totalFunctionsVisited++
        stats.currentClassName = declaration.parentClassName
        stats.currentFunctionFqName =
            (stats.currentClassName ?: "<top>") + "::" + declaration.name.asString()
        scopeStack.addLast(
            linkedMapOf<String, IrValueDeclaration>().apply {
                declaration.valueParameters
                    .filter(::isVisibleLocal)
                    .forEach { put(it.name.asString(), it) }
            },
        )
        declaration.transformChildrenVoid(this)
        scopeStack.removeLast()
        return declaration
    }

    private fun <T : IrElement> rewriteStatementContainer(
        container: T,
        statements: MutableList<IrStatement>,
    ): T {
        scopeStack.addLast(linkedMapOf())
        val rewritten = statements.map { statement ->
            val transformed = statement.transform(this, null) as IrStatement
            if (transformed is IrValueDeclaration && isVisibleLocal(transformed)) {
                scopeStack.last()[transformed.name.asString()] = transformed
            }
            transformed
        }
        statements.clear()
        statements.addAll(rewritten)
        scopeStack.removeLast()
        return container
    }

    override fun visitBlockBody(body: IrBlockBody): IrBody =
        rewriteStatementContainer(body, body.statements)

    override fun visitBlock(expression: IrBlock): IrExpression =
        rewriteStatementContainer(expression, expression.statements)

    override fun visitComposite(expression: IrComposite): IrExpression =
        rewriteStatementContainer(expression, expression.statements)

    override fun visitCall(expression: IrCall): IrExpression {
        expression.transformChildrenVoid(this)
        val rewritten: IrExpression
        val didMatch = when {
            expression.isChronoLoggerCall() -> {
                rewritten = rewriteLoggerCall(
                    expression,
                    pluginContext,
                    helperSymbols,
                    scopeStack.visibleLocals(configuration),
                )
                true
            }
            expression.isTopLevelChronoCall("withTrace") -> {
                rewritten = rewriteSpanCall(
                    expression,
                    pluginContext,
                    helperSymbols.withTraceCaptured,
                    helperSymbols,
                    scopeStack.visibleLocals(configuration),
                )
                true
            }
            expression.isTopLevelChronoCall("withSpan") -> {
                rewritten = rewriteSpanCall(
                    expression,
                    pluginContext,
                    helperSymbols.withSpanCaptured,
                    helperSymbols,
                    scopeStack.visibleLocals(configuration),
                )
                true
            }
            else -> {
                rewritten = expression
                false
            }
        }
        if (didMatch) {
            stats.totalCallsRewritten++
            val cls = stats.currentClassName ?: "unknown"
            val fn = stats.currentFunctionFqName ?: "<unknown>"
            val fns = stats.classStats.getOrPut(cls) { mutableSetOf() }
            fns.add(fn)
        }
        return rewritten
    }
}

private fun rewriteLoggerCall(
    expression: IrCall,
    pluginContext: IrPluginContext,
    helperSymbols: HelperSymbols,
    visibleLocals: List<IrValueDeclaration>,
): IrExpression {
    if (visibleLocals.isEmpty()) {
        return expression
    }

    val builder = DeclarationIrBuilder(
        pluginContext,
        expression.symbol,
        expression.startOffset,
        expression.endOffset,
    )
    val localsMap = builder.buildLocalsMap(helperSymbols, visibleLocals)
    val existingFields = expression.getValueArgument(1)
    val mergedFields = builder.irCall(helperSymbols.mergeCaptureFields).apply {
        putValueArgument(0, existingFields ?: builder.irCall(helperSymbols.emptyMap))
        putValueArgument(1, localsMap)
    }
    expression.putValueArgument(1, mergedFields)
    return expression
}

private fun rewriteSpanCall(
    expression: IrCall,
    pluginContext: IrPluginContext,
    replacement: IrFunctionSymbol,
    helperSymbols: HelperSymbols,
    visibleLocals: List<IrValueDeclaration>,
): IrExpression {
    if (visibleLocals.isEmpty()) {
        return expression
    }

    val builder = DeclarationIrBuilder(
        pluginContext,
        replacement,
        expression.startOffset,
        expression.endOffset,
    )
    val localsMap = builder.buildLocalsMap(helperSymbols, visibleLocals)
    return builder.irCall(replacement).apply {
        for (index in 0 until expression.typeArgumentsCount) {
            putTypeArgument(index, expression.getTypeArgument(index))
        }
        putValueArgument(0, expression.getValueArgument(0))
        putValueArgument(1, localsMap)
        putValueArgument(2, expression.getValueArgument(expression.valueArgumentsCount - 1))
    }
}

private fun isVisibleLocal(value: IrValueDeclaration): Boolean {
    val name = value.name.asString()
    return !name.startsWith("$") && !name.startsWith("<")
}

/**
 * Flatten the scope stack into a list of visible locals, applying
 * [IrCoreConfiguration.shouldRedact] first and then truncating to
 * [IrCoreConfiguration.captureDepth]. Inner-scope declarations
 * shadow outer-scope ones with the same name (preserved by the
 * `linkedMapOf` insertion order from the stack walk).
 */
private fun ArrayDeque<LinkedHashMap<String, IrValueDeclaration>>.visibleLocals(
    configuration: IrCoreConfiguration,
): List<IrValueDeclaration> {
    val merged = linkedMapOf<String, IrValueDeclaration>()
    for (scope in this) {
        for ((name, value) in scope) {
            merged[name] = value
        }
    }
    val limit = configuration.captureDepth.coerceAtLeast(0)
    return merged.values
        .toList()
        .filterNot { configuration.shouldRedact(it.name.asString()) }
        .take(limit)
}

private fun IrCall.isChronoLoggerCall(): Boolean {
    val owner = symbol.owner
    return owner.name.asString() in LOGGER_METHODS &&
        owner.parent is IrClass &&
        (owner.parent as IrClass).name.asString() == "ChronoLogger" &&
        (owner.parent as IrClass).parent is IrPackageFragment &&
        ((owner.parent as IrClass).parent as IrPackageFragment).packageFqName.asString() == CHRONO_PACKAGE
}

private fun IrCall.isTopLevelChronoCall(name: String): Boolean {
    val owner = symbol.owner
    val parent = owner.parent
    return owner.name.asString() == name &&
        parent is IrPackageFragment &&
        parent.packageFqName.asString() == CHRONO_PACKAGE
}

private fun DeclarationIrBuilder.buildLocalsMap(
    helperSymbols: HelperSymbols,
    locals: List<IrValueDeclaration>,
): IrExpression {
    val pairConstructor = helperSymbols.pairClass.owner.declarations
        .filterIsInstance<IrConstructor>()
        .firstOrNull()?.symbol
        ?: error(
            "Cannot resolve kotlin.Pair constructor. " +
                "The Kotlin stdlib must be on the compiler classpath.",
        )
    val pairType = pairConstructor.owner.returnType
    val pairs = locals.map { local ->
        irCallConstructor(pairConstructor, emptyList()).apply {
            putValueArgument(0, irString(local.name.asString()))
            putValueArgument(1, irGet(local))
        }
    }
    return irCall(helperSymbols.mapOf).apply {
        putValueArgument(0, irVararg(pairType, pairs))
    }
}
