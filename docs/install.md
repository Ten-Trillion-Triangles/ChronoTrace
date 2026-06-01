# ChronoTrace Install and Build Guide

This guide covers building ChronoTrace from source, running the server in all three storage modes, and running tests. For SDK integration and API reference, see the [User Manual](user-manual.md). For deployment configuration, see the [Deployment Guide](deployment-guide.md).

**Version:** 1.0.0 (Kotlin 2.2.21, Gradle 8.x)

---

## 1. Prerequisites

| Component | Requirement |
|-----------|-------------|
| Java JDK | Java 17+ for JVM targets; Java 25+ recommended for running the server |
| Node.js | 18+ for JS/Wasm targets and the TypeScript SDK |
| npm | 10+ for TypeScript SDK |
| Docker + Docker Compose | For ClickHouse/Valkey storage and integration tests |
| Gradle | 8.x (included via `gradlew` wrapper; do not use system Gradle) |

Verify your setup:

```bash
java -version   # should show 17 or higher
node -v         # should show 18 or higher
docker --version
./gradlew --version
```

---

## 2. Building from Source

Clone the repository and build everything:

```bash
git clone https://github.com/your-org/chronotrace.git
cd chronotrace
./gradlew build
```

`build` compiles all modules and runs unit tests (skipping integration tests). To skip tests entirely:

```bash
./gradlew build -x test
```

### Individual Module Build Targets

| Command | Output |
|---------|--------|
| `./gradlew :chronotrace-server:installDist` | Runnable binary at `chronotrace-server/build/install/chronotrace-server/bin/chronotrace-server` |
| `./gradlew :chronotrace-kotlin-plugin:jar` | Compiler plugin JAR at `chronotrace-kotlin-plugin/build/libs/` |
| `./gradlew :sdk-kmp:publishToMavenLocal` | Publishes all KMP targets (JVM/JS/Wasm) to Maven Local |
| `./gradlew :chronotrace-contract:generateTypeScriptContracts` | Generates TypeScript contract types from Kotlin |
| `./gradlew :sdk-kmp:jvmJar` | JVM-only JAR |

Run the server distribution directly (without Gradle):

```bash
./chronotrace-server/build/install/chronotrace-server/bin/chronotrace-server
```

---

## 3. Running the Server

The server supports three storage backends. Start infrastructure with Docker Compose first when using ClickHouse or Valkey.

### In-Memory Mode (No Persistence)

```bash
./gradlew :chronotrace-server:run
```

All data is lost on restart. Suitable for local development and testing.

### File-Backed Mode

```bash
CHRONOTRACE_STORAGE_MODE=file \
CHRONOTRACE_DATA_DIR=/tmp/chronotrace \
./gradlew :chronotrace-server:run
```

Records are stored as JSON at `$CHRONOTRACE_DATA_DIR/chronotrace_store.json`.

### ClickHouse + Valkey Mode (Production)

Start the infrastructure:

```bash
docker compose up -d
```

Then run the server:

```bash
CHRONOTRACE_STORAGE_MODE=clickhouse \
CHRONOTRACE_CLICKHOUSE_JDBC_URL=jdbc:clickhouse://localhost:8123/default \
CHRONOTRACE_VALKEY_HOST=localhost \
./gradlew :chronotrace-server:run
```

The server listens on `http://localhost:8080` in all modes.

---

## 4. Running Tests

### All Tests

```bash
./gradlew test
```

### By Module

```bash
./gradlew :chronotrace-server:test        # Server unit tests
./gradlew :sdk-kmp:jvmTest                # Kotlin JVM tests
./gradlew :sdk-kmp:jsTest                 # Kotlin JS tests
./gradlew :sdk-kmp:wasmJsTest             # Kotlin WasmJs tests
./gradlew :chronotrace-kotlin-plugin:test # Compiler plugin tests
./gradlew :chronotrace-contract:test      # Contract serialization tests
```

### TypeScript SDK Tests

```bash
cd sdk-ts && npm install && npm test
```

### Docker Integration Tests

```bash
docker compose up -d
./gradlew :chronotrace-server:test --tests "*IntegrationTest"
docker compose down
```

---

## 5. Docker Compose Services

The `docker-compose.yml` defines three services:

| Service | Image | Host Ports | Description |
|---------|-------|------------|-------------|
| `chronotrace-server` | Built from `chronotrace-server/Dockerfile` | 8081 → 8080 | ChronoTrace server with ClickHouse storage |
| `clickhouse` | `clickhouse/clickhouse-server:25.1` | 18123 → 8123 (HTTP), 19000 → 9000 (native) | Columnar database for trace storage |
| `valkey` | `valkey/valkey:8.0` | 16379 → 6379 | Key-value store for rate limiting and purge job state |

Start all services:

```bash
docker compose up -d
```

Stop all services:

```bash
docker compose down
```

Stop and remove volumes (deletes all stored data):

```bash
docker compose down -v
```

---

## 6. TypeScript SDK Build

```bash
cd sdk-ts && npm install && npm run build
```

This compiles TypeScript source to JavaScript in the `dist/` directory.

### Verifying Contract Generation

```bash
cd sdk-ts && npm run check:contracts
```

This validates that generated TypeScript contracts match the Kotlin source definitions. Run after modifying `chronotrace-contract` types.

---

## 7. Publishing the Kotlin Multiplatform SDK

Publish all targets (JVM, JS, Wasm) to your local Maven repository:

```bash
./gradlew :sdk-kmp:publishToMavenLocal
```

After publishing, add the JVM target to a Gradle project:

```kotlin
dependencies {
    implementation("com.chronotrace:sdk-kmp-jvm:1.0.0")
}
```

For other targets, the Maven coordinates are:

| Target | Coordinate |
|--------|------------|
| JVM | `com.chronotrace:sdk-kmp-jvm:1.0.0` |
| JS | `com.chronotrace:sdk-kmp-js:1.0.0` |
| WasmJs | `com.chronotrace:sdk-kmp-wasmjs:1.0.0` |
| Metadata | `com.chronotrace:sdk-kmp:1.0.0` |

Apply the Kotlin compiler plugin to enable automatic local variable capture:

```kotlin
plugins {
    id("org.chronotrace.kotlin-plugin") version "1.0.0"
}

chronotrace {
    captureLocals.set(true)
}
```

---

## 8. Gradle Plugin Build

The `chronotrace-kotlin-plugin-gradle` module packages the K2 IR compiler plugin as a Gradle plugin. Build the plugin JAR:

```bash
./gradlew :chronotrace-kotlin-plugin-gradle:jar
```

Output: `chronotrace-kotlin-plugin-gradle/build/libs/chronotrace-kotlin-plugin-gradle-1.0.0.jar`

Apply it in your JVM project:

```kotlin
plugins {
    id("org.chronotrace.kotlin-plugin") version "1.0.0"
}
```