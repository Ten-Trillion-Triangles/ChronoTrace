## Plan Overview
- **Total phases**: 2
- **Agents involved**: coder, tester
- **Estimated effort**: Fix 2 remaining production gaps in ChronoTrace

## Phase 1: Ktor TLS/sslConnector Wiring
### Agent: coder
### Objective: Wire TlsConfig into Ktor embeddedServer via sslConnector

### Files to Modify
- `chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoTraceServer.kt`

### Implementation Details
Wire the Ktor 3.x sslConnector using the loaded KeyStore from TlsConfig. The sslConnector call must use `()->CharArray` password providers and proper TLSConfig construction. When tlsConfig.isConfigured, the server should start HTTPS on HTTPS_PORT (default 8443) instead of plain HTTP.

### Validation
- `./gradlew :chronotrace-server:build` passes
- Generate test keystore, set TLS env vars, start server, verify HTTPS on 8443

### Dependencies
- Blocked by: None
- Blocks: Phase 2

---

## Phase 2: IR Plugin Gradle TestKit Integration Tests
### Agent: tester
### Objective: Write integration tests using Gradle TestKit to verify ChronoTrace IR plugin actually instruments ChronoLogger calls

### Files to Create
- `chronotrace-kotlin-plugin/src/test/kotlin/org/chronotrace/plugin/ChronoTraceIrGenerationExtensionIntegrationTest.kt`

### Implementation Details
Use Gradle TestKit to create temporary test projects with Kotlin source using ChronoLogger, apply ChronoTraceGradlePlugin, run build, and verify instrumentation output (println with "ChronoTrace: X functions instrumented"). Test basic instrumentation, scope tracking, lambda capture, synthetic variable filtering, and graceful degradation.

### Validation
- `./gradlew :chronotrace-kotlin-plugin:test` passes with new tests
- All other tests (server 186, sdk-ts 82) continue to pass

### Dependencies
- Blocked by: Phase 1
- Blocks: None