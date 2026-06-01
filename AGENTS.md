# AGENTS.md — ChronoTrace Agent Onboarding

This document is the entry point for AI agents (and human contributors) working on the
ChronoTrace codebase. Read it before making non-trivial changes.

## Repository layout

```
chronotrace-contract/     # Canonical data contracts (KMP module).
                          # Source of truth for SDK <-> server payload shapes.
chronotrace-server/       # Ktor server. Storage backends (file, in-memory, ClickHouse).
sdk-kmp/                  # Multiplatform SDK (JVM, JS, WasmJs).
                          # Produces sdk-kmp-jvm, sdk-kmp-js, sdk-kmp-wasm artifacts.
sdk-ts/                   # TypeScript SDK + Vite plugin.
chronotrace-kotlin-plugin/        # K2 IR compiler plugin (JVM).
chronotrace-kotlin-plugin-js/     # K2 IR compiler plugin (JS).
chronotrace-kotlin-plugin-wasm/   # K2 IR compiler plugin (Wasm).
chronotrace-kotlin-plugin-gradle/ # Gradle plugin that wires the JVM IR plugin into
                                  # a consumer project's compileKotlin task.
chronotrace-ir-core/      # Shared IR-transformation logic. Contains
                          # HelperSymbols, IrCoreExtraction (the visitor), and
                          # IrCoreConfiguration (pluginOptions). Compiles as a
                          # standalone Kotlin JVM library and is depended on by
                          # the three plugin variants.
                          #
                          # As of 2026-06-01 the shared code is in place and
                          # builds, but the three plugin variants (JVM/JS/Wasm)
                          # still hold their own ~290 LoC each of equivalent
                          # code. A future refactor (planned for 1.1.0) will
                          # thin each variant to a ~30 LoC wrapper that calls
                          # into this module. Until then, the duplicated code
                          # in the three variants MUST be kept in lock-step
                          # with this module — the JVM variant's tests
                          # (SuspendFunctionInstrumentationTest,
                          # ChronoTraceIrGenerationExtensionIntegrationTest)
                          # are the source of truth for correctness.
load-test/                # k6 stress/smoke scripts.
samples/                  # Tiny example apps that exercise the SDK.
docs/                     # User manual, architecture notes, deployment guide.
```

## Build & test

```bash
./gradlew clean build                                       # full multi-module build
./gradlew :chronotrace-server:test                         # server unit/integration tests
./gradlew :sdk-kmp:jvmTest :sdk-kmp:jsNodeTest :sdk-kmp:wasmJsNodeTest
./gradlew :chronotrace-kotlin-plugin:test :chronotrace-kotlin-plugin-js:test :chronotrace-kotlin-plugin-wasm:test
./gradlew :chronotrace-kotlin-plugin-gradle:test
./gradlew :chronotrace-contract:test
cd sdk-ts && npm install && npm test
```

## TDD discipline

1. Read the file you are about to change.
2. Write a failing test that demonstrates the bug.
3. Apply the fix.
4. Run the test, confirm it passes.
5. Run the full module test suite to confirm no regressions.

Tests that cannot be written (doc fixes, cosmetic changes) should be flagged in the
final report with a "no test" rationale.

## What NOT to do

- Do not change the Gradle version in `gradle/wrapper/`.
- Do not add new modules to `settings.gradle.kts` without prior approval.
- Do not extend `.gitignore` with patterns the user has not asked for.
- Do not reformat XML/YAML/Gradle files beyond the lines you are actually changing.

## Where to look first

- Architecture overview: `docs/architecture.md` (if it exists; otherwise `README.md`).
- Public API surface: `sdk-kmp/src/commonMain/` and `sdk-ts/src/`.
- Contract types: `chronotrace-contract/src/commonMain/.../ChronoContracts.kt`.
- Server routing: `chronotrace-server/src/main/.../ServerModule.kt`.
