@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package org.chronotrace.contract

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Phase 8: Verifies that the TypeScript contract generator surfaces a clear
 * error when it encounters a [SerialKind] it does not know how to translate
 * (e.g. CONTEXTUAL) instead of silently emitting the literal string "unknown".
 */
class UnknownSerialKindThrowsTest {

    @Serializable(with = ContextualSerializer::class)
    data class ContextualPayload(val value: String)

    object ContextualSerializer : KSerializer<ContextualPayload> {
        override val descriptor: SerialDescriptor = buildSerialDescriptor(
            "ContextualPayload",
            // CONTEXTUAL is the kind that was silently rendering as "unknown" before the fix.
            SerialKind.CONTEXTUAL,
        )

        override fun serialize(encoder: Encoder, value: ContextualPayload) {
            encoder.encodeString(value.value)
        }

        override fun deserialize(decoder: Decoder): ContextualPayload {
            return ContextualPayload(decoder.decodeString())
        }
    }

    @Test
    fun `generator throws on unsupported SerialKind`() {
        val descriptor = ContextualSerializer.descriptor
        val gen = TypeScriptContractGenerator()
        val method = gen.javaClass.getDeclaredMethod("toTsType", SerialDescriptor::class.java)
        method.isAccessible = true
        val exception = assertFailsWith<Exception> {
            method.invoke(gen, descriptor)
        }
        val msg = exception.cause?.message ?: exception.message ?: ""
        kotlin.test.assertTrue(
            msg.contains("Unsupported SerialKind") || msg.contains("CONTEXTUAL"),
            "Expected error message to mention 'Unsupported SerialKind' or 'CONTEXTUAL', was: $msg",
        )
    }

    @Test
    fun `generator succeeds for fully supported contracts`() {
        // Sanity check: the existing contracts (e.g. ClientMetadata) must continue to render.
        val gen = TypeScriptContractGenerator()
        val text = gen.render()
        kotlin.test.assertTrue(text.contains("ClientMetadata"), "generated output should mention ClientMetadata")
    }
}
