# ChronoTrace Full Ship-Readiness Remediation Plan

## Context

The previous targeted remediation (the 9-phase plan this file previously held) closed the 11 items from the first audit. A subsequent **6-phase comprehensive audit** (`/tmp/claude-1000/-home-cage-Desktop-Workspaces-ChronoTrace/0c02f66d-947f-4d3a-b630-5da34cc894fe/tasks/whwb3oj5c.output`, run 2026-06-01) returned verdict **NOT-SHIP-READY** with **4 critical, 11 high, 12 medium, 25 low** findings. The targeted fixes from the prior plan were insufficient — the deeper audit exposed:

- The **K2 IR headline feature is silently broken**: `ChronoLogger` calls inside `suspend fun` bodies are NOT instrumented. The integration test self-documents this with a "known issue" comment and asserts a regex that matches the literal `0 functions instrumented`. `FunctionCountTest` passes by asserting only `TaskOutcome.SUCCESS` and absence of `e:` lines — not actual instrumented counts.
- **JS HttpTransport is non-functional in real environments**: `HttpTransport.js.kt:71` calls `js("fetch(...)")` and reads `response.status as Int` directly off the returned `Promise` (not the resolved `Response`). The mock-only happy-path test hides the bug.
- **TLS is announced but not wired to the Netty engine**: `ChronoTraceServer.kt:51-62` builds `TlsConfig.fromEnvironment()` and `println`s it; the `embeddedServer(Netty, port, host)` call has zero SSL parameters. No `keyStore`, `trustStore`, or `sslPort` reach the engine.
- **`docker-compose.yml` mounts a deleted file**: `clickhouse-user-config.xml` is `D` in `git status` but `docker-compose.yml:25` still mounts it. The documented ClickHouse quickstart fails on a clean checkout.

In addition: 3 near-identical IR extensions (~300 LoC each, diff = `package` + class name + log prefix), native target FDs leak (CIO HttpClient never closed), the JVM IR plugin JAR is wired to Native compileKotlin tasks that ignore it, sdk-ts WS transport has no reconnect, `QuotaTracker` has a `getOrPut` race, `HelperSymbols` uses `.single()!!` with no diagnostic, version drift across `gradle.properties` (1.0.0) / `build.gradle.kts` root (1.0.0-SNAPSHOT) / subprojects, no git remote, 30+ modified files + 8 stale untracked entries in the working tree, and a 1-test `chronotrace-contract` module.

**User directive**: "Fix all of these issues in full. TDD, and prove working." Scope: **all 52 audit items** (4 critical + 11 high + 12 medium + 25 low). Evidence: **full build + all test tasks green + live smoke + final re-audit returns SHIP-READY**.

## Approach

The minimum-change principle from the prior plan still applies, but it was insufficient because the headline feature is broken, not just incomplete. The strategy is now:

1. **TDD discipline throughout**: for every fix, write a failing test that demonstrates the bug → apply the fix → confirm the test passes. No "we'll add tests later."
2. **Shared core for the 3 IR extensions**: extract a `chronotrace-ir-core` module containing `HelperSymbols`, the visitor logic, `findInnerSuspendLambdaFunction`, and the `classStats` counter. Each of the 3 plugin variants becomes a thin wrapper (~30 LoC). This is the precondition for fixing the suspend-matching bug once instead of three times.
3. **Real tests replace mocks**: replace mock-only `HttpTransportJsTest` with a test that hits a real local HTTP server; replace reflection-only `ChronoTraceGradlePluginTest` with a real `@TempDir` + `GradleRunner` test.
4. **Single source of truth for version**: `gradle.properties CHRONOTRACE_VERSION=1.0.0` is the only declared value; every subproject's `build.gradle.kts` reads `project.parent?.extra["CHRONOTRACE_VERSION"]` or `rootProject.version` consistently; `sdk-ts/package.json` version is set in `generateTypeScriptContracts` step or hardcoded to match.
5. **Tighten security model**: remove `mTLS` from `AuthConfig` union (it is not implemented at the network layer and silent-fails); emit a startup WARN when `authMode=none`.
6. **Repository cleanup as a precondition**: delete the entire `md/` research artifact directory, `.hermes/plans/`, all `*.tgz`, all `*.bak`, the committed `hs_err_pid*.log`, `token_response.kt`, `accelbyte-sdk/`, `bui/`, `tstep-enhancements/`, `.codex`, `tube/`, the empty `clickhouse-user-config.xml` (will be restored as part of the docker fix), and the stale `docs/maestro/`. Add patterns to `.gitignore`.

## Phase Plan

12 phases. Phase 0 is foundation and unblocks the rest. Phases 1–9 are largely independent and can run sequentially or be batched. Phases 10–11 close out medium/low items. Phase 12 is the final re-audit.

### Phase 0 — Foundation, hygiene, version source of truth

**Objective**: working tree is clean, version is consistent, shared IR core exists, and CI/build infrastructure is in place.

- Restore `/home/cage/Desktop/Workspaces/ChronoTrace/clickhouse-user-config.xml` with the contents the docker mount expects (audit for the prior `<?xml ...?><clickhouse>...`).
- Delete stale: `md/` (entire dir, 50+ research markdowns), `.hermes/plans/`, `accelbyte-sdk/`, `bui/`, `tstep-enhancements/`, `.codex`, `tube/`, `token_response.kt` (root scratch), `sdk-ts/chronotrace-sdk-ts-0.1.0.tgz`, `dist/chronotrace-sdk-ts-0.1.0.tgz`, `sdk-kmp/src/wasmJsMain/.../HttpTransport.wasmJs.kt.bak`, `chronotrace-server/hs_err_pid*.log`, `docs/TStep-Code-Index-Enhancement-Plan.md` (untracked, wrong project), `docs/maestro/` (use `docs/maestro/` only inside the archived plans path).
- Add `.gitignore` patterns: `*.bak`, `*.tgz`, `hs_err_pid*.log`, `.hermes/`, `accelbyte-sdk/`, `.codex`, `bui/`, `tstep-enhancements/`, `tube/`, `md/`, `token_response.kt`, `build/`, `dist/`, `.claude/`.
- Document (in CONTRIBUTING.md) the need to add a git remote: `git remote add origin https://github.com/chronotrace/ChronoTrace` (do not perform the actual add; that requires user credentials).
- Reconcile version:
  - `gradle.properties`: confirm `CHRONOTRACE_VERSION=1.0.0`.
  - `build.gradle.kts` (root): `version = "1.0.0"` and add `allprojects { version = rootProject.version }` so subprojects inherit (instead of hardcoding `1.0.0-SNAPSHOT`).
  - Subproject `build.gradle.kts` files (chronotrace-server, sdk-kmp, chronotrace-kotlin-plugin, etc.): remove any hardcoded `version = "1.0.0"`; rely on root inheritance.
  - `sdk-ts/package.json`: change `"version": "1.0.0"` to read from gradle (add `gradlew :sdk-ts:syncVersion` task that writes `package.json` from `CHRONOTRACE_VERSION`, or just hardcode `"1.0.0"` to match and add a comment).

**Create shared core module** `chronotrace-kotlin-plugin/chronotrace-ir-core/`:
- Move `HelperSymbols.kt`, `findInnerSuspendLambdaFunction`, the visitor logic, and `classStats` from `chronotrace-kotlin-plugin` into the core module.
- Update the 3 plugin modules to depend on `chronotrace-ir-core` and just define a thin class extending the shared base.
- Each plugin module drops from ~300 LoC to ~30 LoC.

**Validation**:
- `git status --short` shows only intentional changes (no `D` for clickhouse-user-config.xml, no `??` for md/, .hermes/, accelbyte-sdk/, etc.).
- `./gradlew projects` lists all 8 modules (chronotrace-contract, chronotrace-server, sdk-kmp, chronotrace-kotlin-plugin, chronotrace-kotlin-plugin-js, chronotrace-kotlin-plugin-wasm, chronotrace-kotlin-plugin-gradle, chronotrace-ir-core) without errors.
- `find . -name 'CHRONOTRACE_VERSION'` returns `gradle.properties` only.
- `./gradlew :chronotrace-kotlin-plugin:assemble :chronotrace-kotlin-plugin-js:assemble :chronotrace-kotlin-plugin-wasm:assemble` succeed (the 3 thin wrappers compile against the shared core).

### Phase 1 — CRITICAL #1: Suspend function IR instrumentation (the headline feature)

**Objective**: `ChronoLogger.info` inside a `suspend fun` body is instrumented. The integration test asserts `1+ functions instrumented` (not just `0`).

**TDD** (write this first):
- New test: `chronotrace-kotlin-plugin/src/test/kotlin/org/chronotrace/plugin/SuspendFunctionInstrumentationTest.kt` (Gradle TestKit, `@TempDir`).
- Sample project contains:
  ```kotlin
  suspend fun doWork() {
      ChronoLogger.info("from suspend")  // must be instrumented
  }
  ```
- Assert: the compiled class contains a call to `mergeCaptureFields` (the captured-vars helper) or the captured log fields list is non-empty.
- Assert: daemon log contains `ChronoTrace: [1-9]\d* functions instrumented` (regex with non-zero prefix).

**Fix** (in `chronotrace-ir-core`):
- Rewrite `findInnerSuspendLambdaFunction`: use a `declaration.accept(visitor, null)` strategy that descends into the function body, OR use `IrGenerationExtension`'s `generate` phase extension to instrument the suspend state machine after it's lowered.
- Fix `classStats` tracking bug: `prevF` must be incremented for every instrumented function call, not only the first per class.
- Ensure `visitFunction` (or its replacement) handles `IrSimpleFunction` with `isSuspend = true` by descending into the body via the standard `transformChildrenVoid` recursion.

**Apply to all 3 plugins** (now trivial because the logic lives in shared core).

**Validation**:
- `SuspendFunctionInstrumentationTest` passes (1+ instrumented).
- Existing `ChronoTraceIrGenerationExtensionIntegrationTest` is updated to assert `1+ instrumented` (replacing the regex that matches `0`).
- `FunctionCountTest` is updated to assert `instrumentedFns > 0` (replace the weak `TaskOutcome.SUCCESS` check).
- All 3 plugin test suites green: `./gradlew :chronotrace-kotlin-plugin:test :chronotrace-kotlin-plugin-js:test :chronotrace-kotlin-plugin-wasm:test`.

### Phase 2 — CRITICAL #2: JS HttpTransport actually reads response status

**Objective**: `HttpTransport.js.kt:post()` awaits the fetch response and returns the resolved `Response.status`, not the Promise's non-existent `.status`.

**TDD** (write this first):
- New test: `sdk-kmp/src/jsTest/kotlin/com/chronotrace/sdk/transport/HttpTransportJsRealTest.kt` (Node-based, uses `node:http` to spin up a local server on a random port, returns 200 with body `{"ok":true}`).
- Configure `HttpTransport` with `baseUrl = "http://127.0.0.1:<port>"`.
- Call `transport.send(batch)` (or `retryableSend`).
- Assert: no exception; circuit state stays CLOSED; the test server received the POST with the expected body.
- Second test: server returns 503; assert the transport retries with exponential backoff and circuit transitions to OPEN after threshold.

**Fix** in `sdk-kmp/src/jsMain/kotlin/com/chronotrace/sdk/transport/HttpTransport.js.kt:68-73`:
- Replace `js("fetch(...)")` with the proper async pattern: use a Kotlin `suspend` wrapper that calls `await js("fetch(...)")` via the existing `kotlinx.coroutines.await` extension, OR expose a `@JsFun` helper that returns `Promise<JsAny>` (status code).
- The Wasm variant already does this via `ChronoWasmBootstrap.callJsFetchWithStatus` (line 111). Mirror that pattern.
- Handle the resolved `Response`: read `.status` from the actual Response object.
- If `Response.ok` is false or `status` is non-2xx, throw with the status code so `retryableSend` can do its job.

**Validation**:
- `HttpTransportJsRealTest` passes against a real `node:http` server.
- Existing `HttpTransportJsTest` (mock) still passes.
- `./gradlew :sdk-kmp:jsNodeTest` green.
- Live smoke: `./gradlew :chronotrace-server:run` in one terminal, then a JS test script that sends a batch and asserts the server received it.

### Phase 3 — CRITICAL #3: TLS actually wired to Netty

**Objective**: when `TlsConfig.isConfigured == true`, the Netty engine is configured with `sslPort` + `keyStore` + `trustStore` and the server accepts HTTPS on the configured port.

**TDD** (write this first):
- New test: `chronotrace-server/src/test/kotlin/org/chronotrace/server/TlsWiringTest.kt`.
- Boot the server module with `CHRONOTRACE_TLS_KEYSTORE_PATH`, `CHRONOTRACE_TLS_TRUSTSTORE_PATH`, `CHRONOTRACE_TLS_KEYSTORE_PASSWORD` env vars pointing at test JKS files in `@TempDir`.
- Assert: `TlsConfig.fromEnvironment().keyStore` is non-null after the test (currently the keystore is loaded by `TlsConfig.kt:35-54` but never returned).
- Assert: a `javax.net.ssl.SSLSocket` against `https://localhost:<port>` completes the handshake.

**Fix** in `chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoTraceServer.kt:51-62`:
- `TlsConfig` exposes the loaded `KeyStore` and `TrustManagerFactory` objects (not just paths).
- `embeddedServer(Netty, port = port, host = host, configure = { ... })` block configures `sslPort`, `keyStore`, `trustStore`, `keyAlias`, `keyStorePassword`, `trustStorePassword` when TLS is configured.
- Remove the misleading "documented for ops" comment; replace with a real wiring call.

**Update `TlsConfig.kt`**:
- Add `val keyStore: KeyStore?`, `val trustStore: KeyStore?`, `val keyManagerFactory: KeyManagerFactory?`, `val trustManagerFactory: TrustManagerFactory?` fields populated in `fromEnvironment()`.
- Add `val sslPort: Int` (default same as HTTP port, or env-var override).

**Update `docs/deployment-guide.md`**:
- Remove the "Known Issue" callout about HTTPS not being implemented.
- Add a `## TLS Configuration` section documenting the env vars and a sample `keytool` command for generating JKS.

**Validation**:
- `TlsWiringTest` passes.
- `openssl s_client -connect localhost:8080` (with TLS env vars set) completes the handshake and shows the cert.
- `./gradlew :chronotrace-server:test` green.

### Phase 4 — CRITICAL #4: docker-compose mount + repo hygiene baseline

**Objective**: `docker compose up -d` (ClickHouse + Valkey) succeeds on a clean checkout.

**TDD / verification**:
- New test: `chronotrace-server/src/test/kotlin/org/chronotrace/server/DockerComposeMountTest.kt` — but this requires docker, so it's a manual integration test. Document in `docs/install.md` how to run it.

**Fix**:
- Restore `clickhouse-user-config.xml` at repo root with the original content (from git: `git show HEAD:clickhouse-user-config.xml`).
- The audit noted the file is "empty `<password></password>`" in its deleted state. If the historical content was placeholder, regenerate with a real ClickHouse `users.xml` shape that allows a `default` user with no password (for dev quickstart) or with the documented env-var password. Document this in `docs/clickhouse-schema.md`.
- Update `docs/install.md` to note that the file is committed and required by the docker mount.
- Add `clickhouse-user-config.xml` to the file inventory (it should be present in git).

**Validation**:
- `test -f clickhouse-user-config.xml` succeeds.
- `docker compose config` (validates docker-compose.yml) shows no errors.
- `docker compose up -d clickhouse valkey` succeeds (manual; if docker is unavailable in this environment, document the expected steps and a unit test that validates the file content matches the mount's expected schema).

### Phase 5 — HIGH: Native FDs leak + dead JVM IR plugin JAR on Native

**Objective**: linuxX64/macosX64 `HttpTransport` closes its `HttpClient` on shutdown; Native `compileKotlin` tasks do not receive the JVM K2 IR plugin JAR.

**TDD** (write this first):
- New test: `sdk-kmp/src/linuxX64Test/kotlin/com/chronotrace/sdk/transport/HttpTransportLinuxTest.kt` (and a parallel macosX64Test). Test that creating a `HttpTransport`, calling `send`, and `close()` (or a JVM-like shutdown hook invocation) results in the internal `HttpClient` being closed (assert via reflection or via a counter).
- New test: `sdk-kmp/src/linuxX64Test/kotlin/com/chronotrace/sdk/ChronoRuntimeHooksLinuxTest.kt` (and macosX64) — assert the `atexit` hook is actually invoked when the test process exits cleanly. (Tricky: an `atexit` test typically uses `fork` + `_exit`. Use a child-process pattern: launch a small Kotlin program that registers the hook and exits; assert the parent sees the exit normally AND a side-effect file was written.)

**Fix**:
- `sdk-kmp/src/linuxX64Main/kotlin/com/chronotrace/sdk/transport/HttpTransport.linux.kt:50` and macosX64 variant: add a `close()` method that calls `client.close()`. Make `HttpTransport` implement `AutoCloseable` (or add an explicit `close()` to the common expect class).
- Add a JVM `ShutdownHook` (already exists for ChronoRuntimeHooks) that also closes any open `HttpTransport` instances via a registry. Or simply: register a shutdown hook in the linuxX64/macosX64 actuals that calls `client.close()`.
- `sdk-kmp/build.gradle.kts:131-158`: change the substring-match target wiring to a target-aware lookup. For `linuxX64` and `macosX64` Native compileKotlin tasks, do NOT inject the JVM K2 IR plugin JAR (Native targets cannot use a JVM IR plugin). Either:
  - Don't apply the IR plugin to Native targets at all (the plugin cannot rewrite Native code), OR
  - Make the Native plugin variants (`chronotrace-kotlin-plugin-native` etc.) actual, but acknowledge that the existing audit found the wiring simply doesn't apply to Native (silent no-op).
  - Simplest path: remove the auto-injection for Native targets and document the limitation.

**Validation**:
- `./gradlew :sdk-kmp:linuxX64Test` green.
- `./gradlew :sdk-kmp:macosX64Test` green (or skipped on a Linux box; CI runs on macOS).
- `HttpTransportLinuxTest` asserts the close is invoked.
- `ChronoRuntimeHooksLinuxTest` asserts atexit is invoked.
- `sdk-kmp/build.gradle.kts:131-158` no longer injects the JVM plugin JAR into Native compileKotlin tasks (verify with `./gradlew :sdk-kmp:linuxX64CompileKotlin --info` and inspect the command line).

### Phase 6 — HIGH: sdk-ts WS reconnect + mTLS removal + QuotaTracker race

**Objective**: sdk-ts WebSocket transport auto-reconnects with backoff; mTLS is removed from `AuthConfig`; `QuotaTracker.windows` is atomic.

**TDD** (write this first):
- New test: `sdk-ts/test/webSocketTransportReconnect.test.ts` — start a WS server, kill it, restart on the same port, assert the client reconnects within 5s and resumes sending. Use a counter to confirm the reconnect happened.
- New test: `sdk-ts/test/configMtlsRemoval.test.ts` — assert that constructing `AuthConfig` with `mode: 'mTLS'` is a compile error (if `mTLS` is removed from the union) or that runtime construction throws a clear error.
- New test (server): `chronotrace-server/src/test/kotlin/org/chronotrace/server/QuotaTrackerRaceTest.kt` — fire 100 concurrent `checkQuota` calls for the same keyId; assert the total count recorded equals exactly 100 (no double-counting from `getOrPut` race).

**Fix**:
- `sdk-ts/src/transports/webSocketTransport.ts`: add an exponential backoff reconnect loop (start at 1s, cap at 30s, max attempts unlimited with a small jitter).
- `sdk-ts/src/config.ts`: remove `mTLS` from the `AuthConfig` union. Update the JSDoc to explicitly say "mTLS is not supported; use `apiKey` or `bearer`."
- `sdk-ts/src/transports/httpTransport.ts`: remove the `mTLS` case (no longer reachable).
- `chronotrace-server/.../AuthTypes.kt:169,183,214` (QuotaTracker): replace `map.getOrPut(keyId) { mutableListOf<Long>() }` with `map.computeIfAbsent(keyId) { ConcurrentHashMap.newKey() }` returning a thread-safe list, OR use `Collections.synchronizedMap` wrapping, OR replace the list with an `AtomicLong` counter for the current window and a `ConcurrentLinkedDeque` of window sizes. Add a per-frame WS quota check (currently WS auth is checked once per connection).

**Validation**:
- `cd sdk-ts && npm test` green (all reconnect, mTLS removal, and quota race tests pass).
- `./gradlew :chronotrace-server:test` green.
- Live smoke: start server with `authMode=apiKey`, open a WS connection, send 100 frames, assert the per-frame quota is enforced.

### Phase 7 — HIGH: Plugin Gradle real tests + IrGenerationExtension.Configuration

**Objective**: `chronotrace-kotlin-plugin-gradle` tests use real `GradleRunner`, not reflection; the plugin supports `pluginOptions` for `captureDepth` and `redactionList`.

**TDD** (write this first):
- New test: `chronotrace-kotlin-plugin-gradle/src/test/kotlin/org/chronotrace/gradle/ApplyPluginFunctionalTest.kt` — uses `@TempDir` + `GradleRunner`, applies the plugin to a tiny Kotlin project, runs `:compileKotlin`, asserts the compiler args contain the JVM IR plugin JAR path.
- New test: `chronotrace-kotlin-plugin/src/test/kotlin/org/chronotrace/plugin/IrGenerationExtensionConfigurationTest.kt` — applies the plugin with `pluginOptions = mapOf("captureDepth" to "5", "redactionList" to "password,apiKey")`, asserts the instrumented code honors those options (a `password` field is redacted, a 5-deep call stack is captured).
- Remove or update the existing reflection-only tests: `ChronoTraceGradlePluginTest`, `TaskCreationTest`, `VersionCompatibilityTest` — keep as smoke tests but add a comment noting they are NOT the functional suite. Or delete them; the new functional tests cover the same surface.

**Fix**:
- `chronotrace-kotlin-plugin/src/main/kotlin/org/chronotrace/plugin/ChronoTraceCompilerPluginRegistrar.kt`: implement `IrGenerationExtension.Configuration.pluginOptions: Map<String, String>` and `requiredOptions: Set<String>`. Read `captureDepth` and `redactionList` from `pluginOptions`.
- Update `ChronoTraceIrGenerationExtension` to honor the config (pass to `HelperSymbols` or to the visitCall logic).
- Remove the `@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")` at the top of the plugin files; fix any actual deprecation warnings that surface.
- `chronotrace-kotlin-plugin-gradle/src/main/kotlin/.../ChronoTraceKotlinPlugin.kt`: when applying the plugin, pass the config from Gradle to the K2 plugin's `pluginOptions`.

**Validation**:
- `ApplyPluginFunctionalTest` passes (real GradleRunner).
- `IrGenerationExtensionConfigurationTest` passes.
- `./gradlew :chronotrace-kotlin-plugin-gradle:test :chronotrace-kotlin-plugin:test` green.
- No deprecation warnings in `./gradlew clean build --warning-mode=all`.

### Phase 8 — HIGH: chronotrace-contract test coverage + generator hardening + maven-publish

**Objective**: `chronotrace-contract` has property-based round-trip tests for all 28 DTOs; `TypeScriptContractGenerator` throws on unknown `SerialKind`; the module is publishable to Maven Local.

**TDD** (write this first):
- New tests: `chronotrace-contract/src/commonTest/kotlin/org/chronotrace/contract/ChronoContractsRoundTripTest.kt` — uses `kotlinx.serialization` + a property-based framework (or hand-rolled fuzz) to round-trip all 28 types. Asserts `encode → decode == original` for each.
- New test: `chronotrace-contract/src/jvmTest/kotlin/org/chronotrace/contract/TypeScriptContractGeneratorTest.kt` — feeds in 5 known inputs and asserts the generated TypeScript matches expected snapshots.
- New test: `chronotrace-contract/src/jvmTest/kotlin/org/chronotrace/contract/UnknownSerialKindThrowsTest.kt` — adds a fake `@Serializable` type with a `SerialKind.CONTEXTUAL` field (which the generator doesn't currently handle) and asserts the generator throws with a clear error (instead of silently emitting `unknown`).

**Fix**:
- `chronotrace-contract/src/jvmMain/kotlin/org/chronotrace/contract/TypeScriptContractGenerator.kt:112` — replace `else -> 'unknown'` with `throw IllegalStateException("Unsupported SerialKind: $kind for $declaration")`.
- `chronotrace-contract/src/jvmMain/kotlin/org/chronotrace/contract/TypeScriptContractGenerator.kt:75,88` — quote-escape enum element names; fix the `Optional` detection (conflate default-value with explicit-null); add JSDoc to generated interfaces.
- `chronotrace-contract/build.gradle.kts` — add `maven-publish` block with `group = "com.chronotrace"`, `version = rootProject.version`.

**Validation**:
- `./gradlew :chronotrace-contract:test` green.
- `./gradlew :chronotrace-contract:check` green (includes `verifyTypeScriptContracts`).
- `./gradlew :chronotrace-contract:publishToMavenLocal` succeeds.
- `ChronoContractsRoundTripTest` covers all 28 types.
- Re-run `./gradlew :chronotrace-contract:generateTypeScriptContracts`; the regenerated `sdk-ts/src/generated/contracts.ts` is byte-equal to the committed version (or differs only in expected ways).

### Phase 9 — HIGH: HelperSymbols diagnostic + sdk-kmp open issues

**Objective**: missing SDK symbols produce a clear diagnostic, not a `NoSuchElementException`; other sdk-kmp high-priority items are closed.

**TDD** (write this first):
- New test: `chronotrace-kotlin-plugin/src/test/kotlin/org/chronotrace/plugin/HelperSymbolsMissingTest.kt` — point `HelperSymbols` at an empty classpath; assert the error message names the missing symbol and the SDK jar path.

**Fix**:
- `chronotrace-kotlin-plugin/.../HelperSymbols.kt:183` (and shared core equivalent) — replace `.single()!!` with `error("Cannot resolve required SDK symbol '$name'. The ChronoTrace SDK must be on the Kotlin compiler classpath. Looked in: $jarPath")`.
- `sdk-kmp/src/jvmMain/.../HttpTransport.jvm.kt:29` and `sdk-kmp/src/commonMain/.../HttpTransport.kt:10` — `devUrlBypass: Boolean` — rename to `allowInsecureBaseUrl: Boolean`, add KDoc warning that it's a dev-only escape hatch and must never be `true` in production.
- `sdk-kmp/.../ChronoCapture.sanitizeLogFields` — make behavior consistent with `FrameSnapshot.localsJson` (either both serialize nested structures or both fail on them). Pick one and document.
- `sdk-kmp/.../ChronoTrace.injectHeaders/extractHeaders` — validate `traceId.length in [1, 64]` and `spanId.length in [1, 32]`; throw `IllegalArgumentException` for invalid IDs.
- `sdk-kmp/.../ChronoTrace.init` — make the `runtime` field `private val runtime: ChronoRuntime by lazy { ... }` so concurrent first-time callers can't race.
- `sdk-kmp/.../ChronoRuntime.resolveCaptureReason` — the audit flagged that both branches return null (line 195). Make it actually resolve based on the calling context, or remove the dead path.
- `sdk-kmp/.../public expect open class HttpTransport` — make the expect `public` (not `internal`) so consumers can type their own wrappers; remove `devUrlBypass` from the public expect (only on JVM actual).

**Validation**:
- `HelperSymbolsMissingTest` passes (clear error message).
- All sdk-kmp tests pass on all 5 targets.
- No `devUrlBypass` in the public expect; only on JVM actual with renamed flag.

### Phase 10 — MEDIUM: 12 medium-priority fixes

**Objective**: close the 12 medium items the audit flagged.

This is a sweep across multiple modules. Items:

1. **server**: `recordAudit` empty overload at `ServerModule.kt:1005` — delete the dead no-op.
2. **server**: `WS auth treats callerKeyId as nullable` — make WS auth reject anonymous connections unless `authMode=none`.
3. **server**: `bootstrap()` silently swallows exceptions — log via SLF4J at WARN with stack trace; only schema version mismatch remains fatal.
4. **server**: File key persistence non-atomic — use `Files.write(temp) + Files.move(temp, real, REPLACE_EXISTING)`.
5. **server**: Error responses leak exception messages — sanitize via a `safeMessage(t: Throwable): String` helper that returns the class name + status, not the full stack.
6. **server**: `QuotaTracker.windows` per-keyId counter race — already covered by Phase 6 tests; ensure the fix uses `AtomicLong` for the window count.
7. **sdk-kmp**: `HttpTransport.devUrlBypass` — covered by Phase 9.
8. **sdk-ts**: `ChronoTrace.init()` is documented as returning a Promise in `client.ts:144` but `index.ts:21` is synchronous — make `init` truly async, return `Promise<void>`, await in callers.
9. **sdk-ts**: Vite plugin source maps — generate source maps instead of `{code, map: null}`.
10. **sdk-ts**: Remote rule regex compilation — cache compiled `RegExp` per rule (Map<ruleId, RegExp>) and validate regex syntax at `upsertRemoteRule` time.
11. **sdk-kmp**: Wasm `ChronoRuntimeHooks` launches coroutine in `GlobalScope` on page unload (no time to complete) — switch to a synchronous flush: serialize the buffer to localStorage and let the next page load re-ingest. OR use `navigator.sendBeacon` for the final flush.
12. **load-test**: stress-test.js saturation detection uses `__VU` constant as a proxy — fix to use the actual stage target VU count from the k6 config; fix `requestsPerSecond` labeling (it's a count, not a rate); update README example payloads to match what scripts generate; add `package.json` so `npm test` (or a smoke) runs in CI.

**TDD**: each fix gets a targeted test before the fix lands. No batch fixes.

**Validation**:
- All sdk-kmp + sdk-ts + server + load-test tests green.
- Each test asserts the specific behavior the fix introduced.

### Phase 11 — LOW: 25 low-priority items + Documentation parity

**Objective**: close the 25 low items + reconcile docs with code.

**TDD**: most low items don't have separate tests; they are correctness/hygiene fixes.

**Fixes (representative)**:
- `load-test/smoke-test.js:19` — remove dead `Counter` import.
- `kotlin-js-store/yarn.lock` — drop modifications, regenerate via `gradlew kotlinUpgradeYarnLock`.
- `docs/TStep-Code-Index-Enhancement-Plan.md` — already deleted in Phase 0.
- `AGENTS.md` — create at repo root.
- `SPECIFICATIONS.md:121` — remove stray Chinese character.
- Java version claim inconsistency: pin everything to JDK 21 (matches Dockerfile).
- `chronotrace-contract` maven-publish — already in Phase 8.
- `chronotrace-contract/ChronoContracts.kt:48` `ExpressionOperator` — either reference it in the remote-rules logic or delete.
- `chronotrace-kotlin-plugin-gradle` — add KDoc to `apply()`, add `README.md`.
- `chronotrace-kotlin-plugin-js` and `-wasm` — replace `println` with proper SLF4J via Kotlin compiler logging.
- `chronotrace-kotlin-plugin` — add KDoc to `ChronoTraceCompilerPluginRegistrar` and `ChronoTraceIrGenerationExtension`.
- `chronotrace-server/McpTooling.kt:532-583` — validate `getValue('logId')`/`getValue('frameId')` keys exist before deref.
- `chronotrace-server/ChronoStore.kt:1329` — fix `countWhere` indentation.
- `chronotrace-server/ChronoStore.kt:1542` and `ServerModule.kt:842-843` — replace `System.err.println` with SLF4J logger.
- `sdk-kmp/README.md` — remove "Apple Silicon support" claim or add `macosArm64` target.
- `sdk-kmp/.../HttpTransport.jvm.kt:147` — broaden the 503 catch to include `Ktor ResponseException` with non-503 message.
- `sdk-kmp/.../ChronoRuntimeHooks.wasmJs.kt:53` — fix the page-unload coroutine timing (covered in Phase 10).
- `sdk-kmp` native test source sets — add `HttpTransport` and buffer tests to linuxX64Test and macosX64Test.
- `sdk-kmp/build.gradle.kts:131` — covered in Phase 5.
- `sdk-kmp/ChronoTrace.kt:9` — covered in Phase 9.
- `sdk-kmp` public `HttpTransport expect open class` — covered in Phase 9.
- `sdk-kmp/.../ChronoRuntime.resolveCaptureReason` — covered in Phase 9.
- `sdk-ts/src/transport.ts:19` `NoopTransport.isConnected() => true` — change to `false` (or document the violation).
- `sdk-ts/capture.ts:10` — throw or warn when a user field collides with `__chronotrace_locals`.
- `sdk-ts README` — fix version (`1.0.0`, not `0.1.0`) and license (`MIT`, not `Apache-2.0`).
- `sdk-ts/buffer.ts:45` `RingBuffer` — either use it in `MemoryQueue` (and accept `BLOCK_CALLER`) or remove the unused export.
- `sdk-ts/instrumentation.ts` — replace `import 'typescript'` with lazy `await import('typescript')` only in the Vite plugin path (not in the runtime SDK).
- `chronotrace-kotlin-plugin` — replace `println` for instrumentation metrics with proper compiler logging.

**Documentation parity fixes**:
- `README.md`: default storage mode (`inMemory` vs `file` — confirm `FILE` per code).
- `README.md`: WebSocket endpoint verb (POST vs WS — `WS /api/v1/ingest/ws`).
- `README.md`: repository structure section (add `-js` and `-wasm` plugin modules, `-ir-core` shared module).
- `docs/user-manual.md`: ChronoConfig signature, fields `Map<String, String>` → `Map<String, Any?>`.
- `CHANGELOG.md` and `RELEASE_NOTES.md`: reconcile 2026-05-16 vs 2026-05-27 release dates.
- `sdk-ts/README.md`: fix version and license.
- Mark mTLS explicitly as not-implemented in user manual.
- `docs/architecture.md` — add the module map and data flow diagram (links from README).

**Validation**:
- All targeted tests pass.
- `grep -r "Apache-2.0" .` returns nothing (or only the LICENSE history).
- `grep -r "0.1.0" sdk-ts/README.md` returns nothing.
- `grep -r "BLOCK_CALLER" sdk-ts/src` returns nothing (or only BLOCK_CALLER is implemented).
- `grep -r "mcp/v1" .` returns nothing.
- `grep -r "CHRONOTRACE_PORT" .` returns nothing.

### Phase 12 — Final re-audit (prove SHIP-READY)

**Objective**: the same 6-phase `chronotrace-ship-readiness-audit` workflow that returned NOT-SHIP-READY now returns SHIP-READY.

- Run `/chronotrace-ship-readiness-audit` (or the equivalent `Workflow({name: "chronotrace-ship-readiness-audit"})` invocation).
- Expected verdict: `SHIP-READY`.
- If NOT-SHIP-READY: identify the remaining issues and route back to the appropriate phase for remediation; iterate until SHIP-READY.

**Validation**:
- `Workflow({name: "chronotrace-ship-readiness-audit"})` returns `{verdict: "SHIP-READY", ...}`.
- The audit output (preserved at `/tmp/claude-1000/.../tasks/whwb3oj5c.output` successor path) lists the same 4 critical + 11 high issues as RESOLVED.

---

## Critical Files (Phase Index)

- `chronotrace-kotlin-plugin/chronotrace-ir-core/` (NEW shared module)
- `chronotrace-kotlin-plugin/.../ChronoTraceIrGenerationExtension.kt` — Phase 1
- `chronotrace-kotlin-plugin/.../HelperSymbols.kt` — Phase 1, Phase 9
- `chronotrace-kotlin-plugin/.../ChronoTraceCompilerPluginRegistrar.kt` — Phase 7
- `chronotrace-kotlin-plugin-js/.../ChronoTraceJsIrGenerationExtension.kt` — Phase 1 (thin wrapper after shared core)
- `chronotrace-kotlin-plugin-wasm/.../ChronoTraceWasmIrGenerationExtension.kt` — Phase 1 (thin wrapper)
- `chronotrace-kotlin-plugin-gradle/.../ChronoTraceKotlinPlugin.kt` — Phase 0, Phase 7
- `sdk-kmp/src/jsMain/.../transport/HttpTransport.js.kt` — Phase 2
- `sdk-kmp/src/linuxX64Main/.../transport/HttpTransport.linux.kt` — Phase 5
- `sdk-kmp/src/macosX64Main/.../transport/HttpTransport.macos.kt` — Phase 5
- `sdk-kmp/src/wasmJsMain/.../ChronoRuntimeHooks.wasmJs.kt` — Phase 10
- `sdk-kmp/src/commonMain/.../ChronoTrace.kt` — Phase 9, Phase 10
- `sdk-kmp/src/commonMain/.../ChronoCapture.kt` — Phase 9
- `sdk-kmp/src/commonMain/.../transport/HttpTransport.kt` — Phase 9
- `sdk-kmp/build.gradle.kts:131-158` — Phase 0, Phase 5
- `chronotrace-server/src/main/.../ChronoTraceServer.kt:51-62` — Phase 3
- `chronotrace-server/src/main/.../TlsConfig.kt` — Phase 3
- `chronotrace-server/src/main/.../AuthTypes.kt:169,183,214` — Phase 6
- `chronotrace-server/src/main/.../ChronoStore.kt:1329,1542,1704` — Phase 9, Phase 10
- `chronotrace-server/src/main/.../ServerModule.kt:842-843,1005` — Phase 10
- `chronotrace-server/src/main/.../McpTooling.kt:532-583` — Phase 11
- `chronotrace-contract/src/jvmMain/.../TypeScriptContractGenerator.kt:25-49,75,88,112` — Phase 8
- `chronotrace-contract/build.gradle.kts` — Phase 8
- `sdk-ts/src/transports/webSocketTransport.ts` — Phase 6
- `sdk-ts/src/transports/httpTransport.ts` — Phase 6, Phase 9
- `sdk-ts/src/config.ts` — Phase 6
- `sdk-ts/src/client.ts` — Phase 10
- `sdk-ts/src/transport.ts` — Phase 11
- `sdk-ts/src/capture.ts` — Phase 11
- `sdk-ts/src/buffer.ts` — Phase 11
- `sdk-ts/src/instrumentation.ts` — Phase 11
- `sdk-ts/vite.ts` — Phase 10
- `sdk-ts/src/remoteRules.ts` — Phase 10
- `sdk-ts/README.md` — Phase 11
- `load-test/stress-test.js` — Phase 10
- `load-test/smoke-test.js` — Phase 11
- `load-test/package.json` (NEW) — Phase 10
- `gradle.properties` — Phase 0
- `build.gradle.kts` (root) — Phase 0
- `.gitignore` — Phase 0
- `clickhouse-user-config.xml` (RESTORE) — Phase 4
- `docs/user-manual.md` — Phase 11
- `docs/clickhouse-schema.md` — Phase 0
- `docs/deployment-guide.md` — Phase 3, Phase 11
- `docs/install.md` — Phase 4
- `docs/architecture.md` (NEW) — Phase 11
- `CHANGELOG.md` — Phase 11
- `RELEASE_NOTES.md` — Phase 11
- `README.md` — Phase 11
- `sdk-kmp/README.md` — Phase 11
- `chronotrace-kotlin-plugin-gradle/README.md` (NEW) — Phase 11
- `CONTRIBUTING.md` — Phase 0, Phase 11
- `SECURITY.md` — Phase 11
- `AGENTS.md` (NEW) — Phase 11

## Execution Strategy

| Phase | Parallel? | Agents | Est. wall time |
|-------|-----------|--------|----------------|
| 0 — Foundation | No | coder + refactor | 30-60 min |
| 1 — Suspend IR | No | coder + tester | 30-60 min |
| 2 — JS HttpTransport | No | coder + tester | 20-30 min |
| 3 — TLS Netty | No | coder + tester | 20-30 min |
| 4 — Docker compose | No | coder | 5-10 min |
| 5 — Native FD + plugin | No | coder + tester | 20-30 min |
| 6 — sdk-ts WS/mTLS/quota | No | coder + tester | 20-30 min |
| 7 — Plugin Gradle + config | No | coder + tester | 20-30 min |
| 8 — Contract tests | No | coder + tester | 15-20 min |
| 9 — HelperSymbols + sdk-kmp | No | coder + tester | 20-30 min |
| 10 — Medium sweep | No | coder + tester | 30-60 min |
| 11 — Low + docs | No | technical-writer + coder | 30-60 min |
| 12 — Re-audit | No | (workflow invocation) | 30-45 min |

Total estimated wall time: **5-7 hours** if all phases run sequentially and no phases are re-run. Phase 0 must complete before 1-11 can start. Phases 1-11 could theoretically be parallelized but they share enough file ownership that contention would dominate. Sequential is the safer choice.

## Risk Classification

| Phase | Risk | Rationale |
|-------|------|-----------|
| 0 | LOW | Mostly deletions + version reconcile. |
| 1 | HIGH | The IR rewrite could break the existing (non-suspend) instrumentation. Mitigated by the existing test suite + new suspend-specific test. |
| 2 | MEDIUM | Adding a real `node:http` test requires Node setup in the gradle test task; verify the test runs in jsNodeTest. |
| 3 | MEDIUM | TLS wiring in Ktor/Netty has many gotchas (keyStore format, password env var parsing, sslPort vs port). The test exercises a self-signed JKS. |
| 4 | LOW | Restore a single file. |
| 5 | MEDIUM | Native atexit test requires forking a child process; tricky on CI. |
| 6 | MEDIUM | WS reconnect with backoff has subtle edge cases (rapid re-disconnect, server not back up yet). |
| 7 | MEDIUM | GradleRunner tests are slow (~10-30s each) and may be flaky in parallel CI. |
| 8 | LOW | Property-based tests need a framework; if not already present, use hand-rolled fuzz. |
| 9 | LOW | Mostly diagnostic improvements + small sdk-kmp fixes. |
| 10 | MEDIUM | Many small fixes; risk is in missing one. Mitigated by re-running the audit at the end. |
| 11 | LOW | Documentation + small low-priority fixes. |
| 12 | LOW | Workflow invocation; if SHIP-READY is not returned, route back. |

## Verification Standard (end-to-end)

After all 11 phases:

```bash
# 1. Full build (all targets)
./gradlew clean build

# 2. All KMP target tests
./gradlew :sdk-kmp:jvmTest :sdk-kmp:jsNodeTest :sdk-kmp:wasmJsNodeTest :sdk-kmp:linuxX64Test :sdk-kmp:macosX64Test

# 3. All plugin tests
./gradlew :chronotrace-kotlin-plugin:test :chronotrace-kotlin-plugin-js:test :chronotrace-kotlin-plugin-wasm:test :chronotrace-kotlin-plugin-gradle:test

# 4. Server tests
./gradlew :chronotrace-server:test

# 5. Contract tests + verify
./gradlew :chronotrace-contract:test :chronotrace-contract:check

# 6. sdk-ts
cd sdk-ts && npm install && npm test && npm run build

# 7. Live smoke (ClickHouse + Valkey via docker)
docker compose up -d clickhouse valkey
./gradlew :chronotrace-server:run &
SERVER_PID=$!
sleep 10
curl -s http://localhost:8080/health
curl -s -X DELETE http://localhost:8080/api/v1/remote-rules/nonexistent  # expect 404
k6 run load-test/smoke-test.js  # expect 100% success
kill $SERVER_PID
docker compose down

# 8. Hygiene
test -f clickhouse-user-config.xml
test ! -d accelbyte-sdk
test ! -d md
test ! -d .hermes
test ! -f sdk-ts/chronotrace-sdk-ts-0.1.0.tgz
test ! -f sdk-kmp/src/wasmJsMain/kotlin/com/chronotrace/sdk/transport/HttpTransport.wasmJs.kt.bak
grep -r "CHRONOTRACE_PORT" . --include="*.md" --include="*.kts" --include="*.kt" && exit 1
grep -r "mcp/v1" . --include="*.md" && exit 1
grep -r "Apache-2.0" . --include="*.ts" --include="*.json" --include="*.md" && exit 1
grep -r "Math.random" sdk-ts/src && exit 1
grep -r "internal fun mergeCaptureFields" sdk-kmp/src/commonMain && exit 1  # now public

# 9. Final ship-readiness audit
# Run /chronotrace-ship-readiness-audit (same workflow that returned NOT-SHIP-READY)
# Expected: verdict SHIP-READY
```

If the final re-audit returns NOT-SHIP-READY, the next-step list in the audit output points to the remaining issues; route back to the relevant phase and re-verify.

## Next Step

On plan approval, this file becomes the implementation plan for the maestro orchestration. Phase 0 begins immediately with the foundation work (deletions, version reconcile, shared IR core). Subsequent phases execute in order.
