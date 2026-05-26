package org.chronotrace.plugin

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
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrComposite
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Tests for [ChronoTraceIrGenerationExtension] graceful degradation behavior.
 * Verifies that the plugin fails gracefully when instrumentation throws,
 * rather than crashing the entire build.
 */
class ChronoTraceIrGenerationExtensionGracefulDegradationTest {

    /**
     * A failing IrPluginContext that throws when instrumentation is attempted.
     * This simulates an edge case where the plugin encounters an unhandleable error
     * during IR transformation (e.g., corrupted class metadata, missing SDK types).
     */
    private class FailingPluginContext : IrPluginContext(
        moduleDescriptor = mock(),
        bindingContext = mock(),
        constantValueResolver = mock(),
        irBuiltIns = mock(),
        symbolTable = org.jetbrains.kotlin.ir.util.SimpleSymbolTable(),
        typeSystemContext = object : org.jetbrains.kotlin.ir.util.TypeSystemContext {
            // Intentionally minimal - we just need something that won't crash on common calls
        },
        compilerConfiguration = org.jetbrains.kotlin.compiler.CompilerConfiguration()
    )

    @Test
    fun `extension does not throw when generate() fails`() {
        // This test verifies TDD step 1: write failing test first
        // The extension should catch exceptions and not propagate them
        
        val extension = ChronoTraceIrGenerationExtension()
        val moduleFragment = mock<IrModuleFragment>()
        val pluginContext = FailingPluginContext()
        
        // Capture System.err to verify warning is printed
        val originalErr = System.err
        val errContent = ByteArrayOutputStream()
        System.setErr(PrintStream(errContent))
        
        try {
            // This should NOT throw - the plugin should catch the error and continue
            assertDoesNotThrow {
                extension.generate(moduleFragment, pluginContext)
            }
        } finally {
            System.setErr(originalErr)
        }
    }

    @Test
    fun `extension logs warning when instrumentation fails`() {
        val extension = ChronoTraceIrGenerationExtension()
        val moduleFragment = mock<IrModuleFragment>()
        val pluginContext = FailingPluginContext()
        
        val originalErr = System.err
        val errContent = ByteArrayOutputStream()
        System.setErr(PrintStream(errContent))
        
        try {
            extension.generate(moduleFragment, pluginContext)
            
            val output = errContent.toString()
            // Should indicate plugin failure in output
            assert(output.contains("ChronoTrace plugin: failed") || output.contains("warning") || output.contains("Warning")) {
                "Expected warning about plugin failure, got: $output"
            }
        } finally {
            System.setErr(originalErr)
        }
    }
}