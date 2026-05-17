# Scan: Build & Deployment

## Files Scanned

- /home/cage/Desktop/Workspaces/ChronoTrace/build.gradle.kts
- /home/cage/Desktop/Workspaces/ChronoTrace/settings.gradle.kts
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/build.gradle.kts
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/build.gradle.kts
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/package.json
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/tsconfig.json
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-kotlin-plugin/build.gradle.kts
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-kotlin-plugin-gradle/build.gradle.kts
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-contract/build.gradle.kts
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/Dockerfile
- /home/cage/Desktop/Workspaces/ChronoTrace/docker-compose.yml
- /home/cage/Desktop/Workspaces/ChronoTrace/.github/workflows/ci.yml
- /home/cage/Desktop/Workspaces/ChronoTrace/.github/workflows/release.yml

## Build System

### Root Project Configuration
- **Group**: com.chronotrace
- **Version**: 0.1.0-SNAPSHOT
- **Kotlin Version**: 2.2.21
- **Build System**: Gradle (Kotlin DSL)
- **Repositories**: Maven Central

### Plugins Declared at Root (apply false)
| Plugin | Version |
|--------|---------|
| kotlin("jvm") | 2.2.21 |
| kotlin("plugin.serialization") | 2.2.21 |
| kotlin("multiplatform") | 2.2.21 |
| io.ktor.plugin | 3.1.1 |

### Included Modules (from settings.gradle.kts)
1. :chronotrace-contract
2. :chronotrace-server
3. :sdk-kmp
4. :chronotrace-kotlin-plugin
5. :chronotrace-kotlin-plugin-gradle

## Per-Module Build Configuration

### chronotrace-contract
- **Type**: Kotlin Multiplatform
- **Plugins**: kotlin("multiplatform"), kotlin("plugin.serialization")
- **Targets**: jvm, js(IR), wasmJs
- **Dependencies**:
  - kotlinx-serialization-json:1.8.0
- **Custom Tasks**:
  - `generateTypeScriptContracts`: Generates sdk-ts/src/generated/contracts.ts from Kotlin
  - `verifyTypeScriptContracts`: Checks if generated TS contracts are stale

### chronotrace-server
- **Type**: Kotlin JVM Application
- **Main Class**: org.chronotrace.server.ChronoTraceServerKt
- **Plugins**: kotlin("jvm"), kotlin("plugin.serialization"), id("io.ktor.plugin"), application
- **Dependencies**:
  - com.clickhouse:clickhouse-jdbc:0.9.1
  - io.ktor:ktor-server-*-jv (core, netty, content-negotiation, websockets, status-pages, call-logging)
  - kotlinx-serialization-*: 1.8.0
  - redis.clients:jedis:5.2.0
  - ch.qos.logback:logback-classic:1.5.18
  - io.github.oshai:kotlin-logging:7.0.14
- **Test Dependencies**:
  - JUnit Jupiter 5.12.2
  - Testcontainers (clickhouse, junit-jupiter) 1.21.4
  - Ktor test host 3.1.1

### sdk-kmp (ChronoTrace Kotlin Multiplatform SDK)
- **Type**: Kotlin Multiplatform
- **Targets**: jvm, js(IR), wasmJs (each with browser and nodejs variants)
- **Dependencies**:
  - chronotrace-contract
  - kotlinx-coroutines-core:1.10.2
  - kotlinx-datetime:0.7.1
  - kotlinx-serialization-json:1.8.0
- **Custom Configuration**:
  - Compiles against chronotrace-kotlin-plugin JAR
  - Uses `-Xexpect-actual-classes` compiler flag
- **Publishing**:
  - Maven publications for jvm, js, wasmJs with artifact IDs: sdk-kmp-jvm, sdk-kmp-js, sdk-kmp-wasm

### chronotrace-kotlin-plugin
- **Type**: Kotlin JVM Library
- **Plugins**: kotlin("jvm")
- **Dependencies**:
  - kotlin("stdlib") (compileOnly)
  - org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.21 (compileOnly)
- **Purpose**: Compiler plugin that provides the tracing IR generation

### chronotrace-kotlin-plugin-gradle
- **Type**: Gradle Plugin
- **Plugins**: kotlin("jvm"), `java-gradle-plugin`
- **Plugin ID**: org.chronotrace.kotlin-plugin
- **Implementation Class**: org.chronotrace.gradle.ChronoTraceKotlinPlugin
- **Dependencies**:
  - gradleApi()
  - kotlin("stdlib")
  - org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.2.21
  - chronotrace-kotlin-plugin

## TypeScript Build

### package.json (sdk-ts)
- **Name**: @chronotrace/sdk-ts
- **Version**: 0.1.0
- **Build Tool**: TypeScript (tsc)
- **Bundler**: None (plain TypeScript compilation)

### npm Scripts
| Script | Command | Purpose |
|--------|---------|---------|
| build | tsc -p tsconfig.json | Compiles TypeScript to dist/ |
| check:contracts | node ./scripts/check-generated-contracts.mjs | Verifies TS contracts match Kotlin |
| test | vitest run | Runs tests with Vitest |

### tsconfig.json
- **Target**: ES2022
- **Module**: CommonJS
- **OutDir**: dist
- **Strict**: true
- **Declaration**: true

## Docker Setup

### docker-compose.yml Services

| Service | Image | Ports | Volumes |
|---------|-------|-------|---------|
| chronotrace-server | build context: . (Dockerfile) | 8080:8080 | none |
| clickhouse | clickhouse/clickhouse-server:25.1 | 8123, 9000 | clickhouse-data:/var/lib/clickhouse |
| valkey | valkey/valkey:8.0 | 6379 | valkey-data:/var/lib/valkey |

### Dockerfile (chronotrace-server)
- **Base Image**: eclipse-temurin:25-jre
- **Build Step**: Runs `./gradlew :chronotrace-server:installDist`
- **Startup Command**: ./chronotrace-server/build/install/chronotrace-server/bin/chronotrace-server

### Environment Variables (docker-compose)
| Variable | Value |
|----------|-------|
| CHRONOTRACE_AUTH_MODE | none |
| CHRONOTRACE_BIND_HOST | 0.0.0.0 |
| CHRONOTRACE_STORAGE_MODE | clickhouse |
| CHRONOTRACE_CLICKHOUSE_JDBC_URL | jdbc:clickhouse://clickhouse:8123/default |
| CHRONOTRACE_CLICKHOUSE_DATABASE | chronotrace |
| CHRONOTRACE_VALKEY_HOST | valkey |
| CHRONOTRACE_VALKEY_PORT | 6379 |

## CI/CD Pipelines

### CI Workflow (.github/workflows/ci.yml)
- **Triggers**: Push to main, PRs to main
- **Jobs**:
  1. **ci**: Build, Test and Contract Check
     - Uses: ubuntu-latest
     - Java 21 (temurin), Node 20, Gradle
     - Steps: Kotlin build, KMP tests (jvm, js, wasmJs, common), server tests, contract tests, TS SDK build/test

  2. **integration-tests**: Docker-based integration tests
     - Runs after ci job
     - Starts docker compose services
     - Runs integration tests against live ClickHouse and Valkey

### Release Workflow (.github/workflows/release.yml)
- **Trigger**: Git tags matching v*
- **Jobs**:
  1. **build**: Build + Test (same as CI)
  2. **docker**: Docker image build & push
     - Multi-platform: linux/amd64, linux/arm64
     - Image: Docker Hub registry with semver tags
  3. **docker-integration**: Docker integration tests
     - Uses GitHub Actions services for ClickHouse and Valkey
  4. **publish-ts-sdk**: Publishes to npm
     - Requires NPM_TOKEN secret
  5. **publish-kmp**: Publishes Kotlin Multiplatform SDK to Maven Local
     - Uploads artifact to GHA

## Deployment Artifacts

| Artifact | Location | Type |
|----------|----------|------|
| Server JAR + installDist | chronotrace-server/build/install/chronotrace-server/ | Application distribution |
| KMP JVM SDK | Maven Local ~/.m2/repository/com/chronotrace/sdk-kmp-jvm/ | Maven JAR |
| KMP JS SDK | Maven Local ~/.m2/repository/com/chronotrace/sdk-kmp-js/ | Maven JAR |
| KMP Wasm SDK | Maven Local ~/.m2/repository/com/chronotrace/sdk-kmp-wasm/ | Maven JAR |
| TypeScript SDK | sdk-ts/dist/ | npm package (@chronotrace/sdk-ts) |
| Docker Image | Docker Hub (org.chronotrace/chronotrace-server) | OCI image |
| Kotlin Compiler Plugin JAR | chronotrace-kotlin-plugin/build/libs/ | Compiler plugin JAR |
| Gradle Plugin JAR | chronotrace-kotlin-plugin-gradle/build/libs/ | Gradle plugin |

## Environment Variables Required

### Server Runtime
| Variable | Description | Default (docker-compose) |
|----------|-------------|------------------------|
| CHRONOTRACE_AUTH_MODE | Auth mode (none, etc.) | none |
| CHRONOTRACE_BIND_HOST | Host to bind server | 0.0.0.0 |
| CHRONOTRACE_STORAGE_MODE | Storage backend (clickhouse) | clickhouse |
| CHRONOTRACE_CLICKHOUSE_JDBC_URL | ClickHouse JDBC connection URL | jdbc:clickhouse://clickhouse:8123/default |
| CHRONOTRACE_CLICKHOUSE_DATABASE | ClickHouse database name | chronotrace |
| CHRONOTRACE_VALKEY_HOST | Valkey/Redis host | valkey |
| CHRONOTRACE_VALKEY_PORT | Valkey/Redis port | 6379 |

### Build/Release (CI/CD secrets)
| Secret | Used By |
|--------|---------|
| DOCKERHUB_USERNAME | Docker image push |
| DOCKERHUB_TOKEN | Docker Hub auth |
| NPM_TOKEN | TS SDK npm publish |
