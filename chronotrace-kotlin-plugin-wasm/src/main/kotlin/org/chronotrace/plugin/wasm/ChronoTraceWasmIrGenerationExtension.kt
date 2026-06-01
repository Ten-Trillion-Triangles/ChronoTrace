@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
@file:OptIn(
    org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi::class,
    org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class,
    org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI::class,
)

package org.chronotrace.plugin.wasm

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
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
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrComposite
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

private const val ChronoPackage = "com.chronotrace.sdk"
private val LoggerMethods = setOf("trace", "debug", "info", "warn", "error", "fatal")

private val IrDeclaration.parentClassName: String?
    get() = (this as? IrFunction)?.parent?.let { parent ->
        when (parent) {
            is IrClass -> parent.name.asString()
            else -> null
        }
    }

/**
 * ChronoTrace Wasm IR Generation Extension.
 *
 * NOTE: Stack capture is not supported on Wasm platform.
 * Wasm has no Error.stack equivalent, so frame snapshots
 * will have callStack: [] but localsJson will still be populated.
 */
class ChronoTraceWasmIrGenerationExtension : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val helperSymbols = HelperSymbols(pluginContext)
        var totalFunctionsVisited = 0
        var totalCallsRewritten = 0
        // className → distinct functions in that class that had at least one call rewritten.
        val classStats = mutableMapOf<String, MutableSet<String>>()
        var currentClassName: String? = null
        var currentFunctionFqName: String? = null

        moduleFragment.transformChildrenVoid(
            object : IrElementTransformerVoid() {
                private val scopeStack = ArrayDeque<LinkedHashMap<String, IrValueDeclaration>>()

                override fun visitFunction(declaration: IrFunction): IrStatement {
                    totalFunctionsVisited++
                    currentClassName = declaration.parentClassName
                    currentFunctionFqName = (currentClassName ?: "<top>") + "::" + declaration.name.asString()
                    scopeStack.addLast(linkedMapOf<String, IrValueDeclaration>().apply {
                        declaration.valueParameters.filter(::isVisibleLocal).forEach { put(it.name.asString(), it) }
                    })
                    // The K2 IR generation phase runs *before* the suspend state-machine
                    // is lowered into the inner Continuation, so a `suspend fun foo` body
                    // is still a regular IrBlock here. The recursive
                    // `transformChildrenVoid(this)` below visits the body and rewrites
                    // any `ChronoLogger.*` / `withTrace` / `withSpan` calls inside.
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
                                scopeStack.visibleLocals(),
                            )
                            true
                        }
                        expression.isTopLevelChronoCall("withTrace") -> {
                            rewritten = rewriteSpanCall(
                                expression,
                                pluginContext,
                                helperSymbols.withTraceCaptured,
                                helperSymbols,
                                scopeStack.visibleLocals(),
                            )
                            true
                        }
                        expression.isTopLevelChronoCall("withSpan") -> {
                            rewritten = rewriteSpanCall(
                                expression,
                                pluginContext,
                                helperSymbols.withSpanCaptured,
                                helperSymbols,
                                scopeStack.visibleLocals(),
                            )
                            true
                        }
                        else -> {
                            rewritten = expression
                            false
                        }
                    }
                    if (didMatch) {
                        totalCallsRewritten++
                        val cls = currentClassName ?: "unknown"
                        val fn = currentFunctionFqName ?: "<unknown>"
                        val fns = classStats.getOrPut(cls) { mutableSetOf() }
                        fns.add(fn)
                    }
                    return rewritten
                }
            },
        )

        val instrumentedFns = classStats.values.sumOf { it.size }
        val capturedLocals = totalCallsRewritten
        println("ChronoTrace[Wasm]: $instrumentedFns functions instrumented, $capturedLocals locals captured")
        classStats.entries.take(5).forEach { (cls, fns) ->
            println("ChronoTrace[Wasm]:   $cls → ${fns.size} fn(s) instrumented (${fns.take(3).joinToString()})")
        }
    }
}

private class HelperSymbols(
    pluginContext: IrPluginContext,
) {
    private val chronoPackage = FqName(ChronoPackage)
    private val kotlinCollections = FqName("kotlin.collections")

    val mergeCaptureFields: IrFunctionSymbol = pluginContext.referenceFunctions(
        CallableId(chronoPackage, Name.identifier("mergeCaptureFields")),
    ).single()
    val withTraceCaptured: IrFunctionSymbol = pluginContext.referenceFunctions(
        CallableId(chronoPackage, Name.identifier("withTraceCaptured")),
    ).single()
    val withSpanCaptured: IrFunctionSymbol = pluginContext.referenceFunctions(
        CallableId(chronoPackage, Name.identifier("withSpanCaptured")),
    ).single()
    val emptyMap: IrFunctionSymbol = pluginContext.referenceFunctions(
        CallableId(kotlinCollections, Name.identifier("emptyMap")),
    ).single()
    val mapOf: IrFunctionSymbol = pluginContext.referenceFunctions(
        CallableId(kotlinCollections, Name.identifier("mapOf")),
    )
        .single { it.owner.valueParameters.singleOrNull()?.varargElementType != null }
    val pairClass: IrClassSymbol = pluginContext.referenceClass(ClassId.topLevel(FqName("kotlin.Pair")))!!
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

    val builder = DeclarationIrBuilder(pluginContext, expression.symbol, expression.startOffset, expression.endOffset)
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

    val builder = DeclarationIrBuilder(pluginContext, replacement, expression.startOffset, expression.endOffset)
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

private fun ArrayDeque<LinkedHashMap<String, IrValueDeclaration>>.visibleLocals(): List<IrValueDeclaration> {
    val merged = linkedMapOf<String, IrValueDeclaration>()
    for (scope in this) {
        for ((name, value) in scope) {
            merged[name] = value
        }
    }
    return merged.values.toList()
}

private fun findInnerSuspendLambdaFunction_REMOVED() {
    // Helper removed during Phase 1 cleanup. The K2 IR generation phase
    // visits the suspend function body directly via the standard
    // IrElementTransformerVoid recursion; no special "descend into inner
    // SuspendLambda" step is required.
}
private fun IrCall.isChronoLoggerCall(): Boolean {
    val owner = symbol.owner
    return owner.name.asString() in LoggerMethods
        && owner.parent is IrClass
        && (owner.parent as IrClass).name.asString() == "ChronoLogger"
        && (owner.parent as IrClass).parent is IrPackageFragment
        && ((owner.parent as IrClass).parent as IrPackageFragment).packageFqName.asString() == ChronoPackage
}

private fun IrCall.isTopLevelChronoCall(name: String): Boolean {
    val owner = symbol.owner
    val parent = owner.parent
    return owner.name.asString() == name
        && parent is IrPackageFragment
        && parent.packageFqName.asString() == ChronoPackage
}

private fun DeclarationIrBuilder.buildLocalsMap(
    helperSymbols: HelperSymbols,
    locals: List<IrValueDeclaration>,
): IrExpression {
    val pairConstructor = helperSymbols.pairClass.owner.declarations.filterIsInstance<IrConstructor>().single().symbol
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
