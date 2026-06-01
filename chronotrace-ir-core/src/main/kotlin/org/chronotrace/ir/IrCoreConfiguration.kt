package org.chronotrace.ir

/**
 * Configuration shared by all three ChronoTrace K2 IR plugin variants
 * (JVM, JS, Wasm).
 *
 * The Kotlin compiler's [org.jetbrains.kotlin.config.CompilerConfiguration]
 * subplugin options for each variant are translated into this object
 * before invoking [applyChronoTraceIr]. A sensible default exists so
 * variants that do not (yet) expose compiler-plugin options can call
 * the entry point with `IrCoreConfiguration()` and get the same
 * behaviour as before the shared-core extraction.
 *
 * @property captureDepth   How many of the visible locals to include in
 *                          the captured-locals map (after redaction).
 *                          Use [Int.MAX_VALUE] to capture all visible
 *                          locals. JVM defaults to `3`; JS and Wasm
 *                          variants currently capture all locals to
 *                          preserve their pre-refactor behaviour.
 * @property redactionList  Comma-separated regex patterns; locals whose
 *                          names match any pattern are excluded from
 *                          the captured-locals map before the
 *                          [captureDepth] cap is applied.
 */
data class IrCoreConfiguration(
    val captureDepth: Int = Int.MAX_VALUE,
    val redactionList: List<String> = emptyList(),
) {
    /** Lazily-evaluated regex view of [redactionList]. */
    val redactionRegexes: List<Regex>
        get() = redactionList.map { Regex(it) }

    /**
     * Whether [localName] matches any of the configured redaction
     * patterns. The pre-refactor JVM extension used full-match
     * semantics (`Regex.matches`), which we preserve here.
     */
    fun shouldRedact(localName: String): Boolean =
        redactionRegexes.any { it.matches(localName) }
}
