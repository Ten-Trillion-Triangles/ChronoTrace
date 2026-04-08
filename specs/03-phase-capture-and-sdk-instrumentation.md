# Phase 2: Capture And SDK Instrumentation

Last reviewed: 2026-03-11

## Goal

Close the largest gap with the original spec: zero-code structural capture across the supported KMP and TS runtime matrix.

## Current Status

Status: `Implemented`

### Already Landed

- Real `FrameSnapshot` emission from both SDK runtimes.
- Linked `LogRecord.linkedFrameId` wiring for auto-capture and manual trace capture.
- Shared serialization metadata flowing through the canonical contract.
- TS source instrumentation helper and Vite plugin wrapper.
- KMP runtime capture path plus K2 compiler-plugin-based hidden-locals injection.
- Runtime health/state tracking in both SDK families.
- Node fatal hook registration and JVM shutdown-hook-driven fatal flush support.
- KMP JVM tests plus JS/Wasm compile verification for the current target matrix.

### Remaining To Complete This Phase

- No new implementation work is required for this phase.
- Follow-up maintenance is expected only if later phases force contract or runtime guarantee refinement.

### Code Entry Points

- `sdk-ts/src/capture.ts`
- `sdk-ts/src/instrumentation.ts`
- `sdk-ts/src/client.ts`
- `sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoCapture.kt`
- `sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoRuntime.kt`
- `chronotrace-kotlin-plugin/src/main/kotlin/org/chronotrace/plugin/ChronoTraceIrGenerationExtension.kt`

### Verification To Re-run

- `./gradlew :sdk-kmp:jvmTest :sdk-kmp:compileKotlinJs :sdk-kmp:compileKotlinWasmJs`
- `cd sdk-ts && npm run check:contracts && npm test && npm run build`

## Workstreams

### KMP Instrumentation

- Delivered as a K2 compiler-plugin path layered on the existing runtime capture API.
- Rewrites `ChronoLogger.*`, `withTrace`, and `withSpan` callsites to inject hidden locals maps.
- Reuses the shared runtime serialization contract instead of a plugin-only format.
- Preserves coroutine propagation by routing injected capture through `ChronoContextElement`.

### TS And JS Instrumentation

- Delivered for the current supported integration target as a source transformer plus Vite wrapper.
- Keeps Node, browser, and worker code paths aligned to the same hidden-locals injection model at the runtime API layer.
- Broader bundler packaging is deferred to later maintenance work and is not phase-blocking.

### Capture Semantics

- Specify:
  - when capture happens
  - which fields are eligible
  - max depth / max size / collection limits
  - cycle handling
  - masking/redaction precedence
  - how auto-capture levels interact with remote rules
- Current repo behavior:
  - `ERROR` and `FATAL` logs emit frame snapshots automatically
  - manual trace/span capture occurs when `captureLocals` are provided
  - remote rules in TS can now trigger capture reasons that emit frame snapshots

### Runtime Reliability

- Runtime state is now explicit in both SDK families:
  - `CONNECTED`
  - `DEGRADED_BUFFERING`
  - `RECONNECT_BACKOFF`
  - `LOCAL_FALLBACK`
  - `FATAL_FLUSH`
- Node and JVM fatal flush hooks now exist at baseline level.
- Browser, worker, JS, and Wasm paths remain best-effort and are explicitly treated as such.

## Required Outputs

- Runtime support matrix for KMP and TS.
- Implemented KMP compiler-plugin path.
- Implemented TS source instrumentation path.
- Unified serialization contract used by both SDK families.

## Acceptance Criteria

- Every supported runtime now has an explicit baseline capture guarantee.
- The implementation no longer relies on manual field passing for the current supported TS and KMP core use cases.
- TS transform support and KMP plugin support are both present in the repository baseline.
