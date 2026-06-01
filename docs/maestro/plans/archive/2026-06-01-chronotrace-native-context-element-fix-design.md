---
title: "Native/Wasm Compile Fix for ChronoContextElement"
created: "2026-06-01T18:50:00Z"
status: "draft"
authors: ["TechLead", "User"]
type: "design"
design_depth: "quick"
task_complexity: "medium"
---

# Native/Wasm Compile Fix for ChronoContextElement

## Problem Statement

`ChronoContextElement` in `sdk-kmp` extends `kotlinx.coroutines.ThreadContextElement` on linuxX64, macosX64. That class is **JVM-only** in kotlinx-coroutines 1.10.2 — verified by inspecting the sources jar:

- `jvmMain/ThreadContextElement.kt` (12,941 bytes) — exists
- `commonMain/internal/ThreadContext.common.kt` (139 bytes) — exposes `internal expect fun threadContextElements(...)` instead
- `nativeMain/internal/ThreadContext.kt` — actual returns `0` (no-op)
- `jsAndWasmSharedMain/internal/ThreadContext.kt` — actual returns `0` (no-op)

Consequence: `:sdk-kmp:compileKotlinLinuxX64` and `:sdk-kmp:compileKotlinMacosX64` fail with `Unresolved reference 'ThreadContextElement'`. The SDK cannot be published for those targets.

WasmJs has three additional pre-existing compile errors that the same fix pass should also address (otherwise we can't claim WasmJs support either):

1. `ChronoRuntimeHooks.wasmJs.kt:53` — `kotlinx.coroutines.GlobalScope.launch` is unresolved; suspend function `flushFatal` cannot be called from a non-coroutine context.
2. `HttpTransport.wasmJs.kt:98` — `js("Date.now()")` is not allowed inside an inline function body in Kotlin/Wasm.

## Approach

### Fix A: linuxX64 + macosX64 actuals

Mirror the JS/WasmJs pattern. Both already exist in the codebase and compile. Use `AbstractCoroutineContextElement(Key)` and initialize the storage in an `init` block. Keep the multi-threaded `AtomicReference` storage backend (Native is multi-threaded; JS/WasmJs use plain `var` because they're single-threaded).

```kotlin
// linuxX64Main/.../ChronoContextStorage.linux.kt
internal actual object ChronoContextStorage {
    private val ref = AtomicReference<ChronoSpanContext?>(null)  // already correct
    actual fun current(): ChronoSpanContext? = ref.load()
    actual fun set(context: ChronoSpanContext?) { ref.store(context) }
}

internal actual class ChronoContextElement actual constructor(
    private val context: ChronoSpanContext?,
) : AbstractCoroutineContextElement(Key) {  // CHANGED: was ThreadContextElement
    internal actual companion object Key : CoroutineContext.Key<ChronoContextElement>
    init { ChronoContextStorage.set(context) }  // NEW
}
```

Same shape for macosX64.

### Fix B: WasmJs `GlobalScope.launch`

Replace `GlobalScope.launch { current.flushFatal() }` with `runBlocking { current.flushFatal() }` inside the synchronous `onUnload` JS callback. The page is unloading — we have one shot to flush, blocking is acceptable.

```kotlin
private fun onUnload(): Unit {
    val current = runtimeRef ?: return
    kotlinx.coroutines.runBlocking { current.flushFatal() }
}
```

### Fix C: WasmJs `js("Date.now()")` in inline function

Replace the inline `js("Date.now()")` with a top-level `@JsFun` external function (mirroring the pattern already used for `isWindowDefined`, `windowAddBeforeUnloadListener`).

```kotlin
@JsFun("() => Date.now()")
private external fun chronoNowMillisNative(): Double

private fun chronoNowMillis(): Long = chronoNowMillisNative().toLong()
```

## Risks

| Risk | Severity | Mitigation |
|------|----------|------------|
| `runBlocking` on page unload blocks the unload event | LOW | This is intentional — we want to flush before unload completes. The browser's beforeunload has a finite budget but a single in-memory flush is fast. |
| `AbstractCoroutineContextElement` does not propagate context across thread switches on Native | LOW | This is the existing behavior. Native targets are rarely multi-threaded in user code paths; if they are, they use the `AtomicReference` storage and call `ChronoContextStorage.current()` directly. |
| Behavior change on linuxX64/macosX64 from `ThreadContextElement` (which never worked) to `AbstractCoroutineContextElement` (works) | LOW | The `ThreadContextElement` version was a compile error — it never ran. The new version is a strict improvement (it compiles). |

## Success Criteria

1. `./gradlew :sdk-kmp:compileKotlinLinuxX64 :sdk-kmp:compileKotlinMacosX64 :sdk-kmp:compileKotlinWasmJs` all pass.
2. `./gradlew :sdk-kmp:linuxX64Test :sdk-kmp:macosX64Test :sdk-kmp:wasmJsNodeTest` all pass.
3. The pre-existing `ChronoCaptureLinuxTest` (relaxed in r2) still passes.
4. No new compile warnings.
