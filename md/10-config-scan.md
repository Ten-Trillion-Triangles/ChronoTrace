# Scan: Configuration

## Files Scanned

1. `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoTrace.kt`
2. `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoRuntime.kt`
3. `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoModels.kt`
4. `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/config.ts`
5. `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ServerModule.kt`
6. `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStoreOptions.kt`
7. `/home/cage/Desktop/Workspaces/ChronoTrace/specs/00-index.md`

---

## SDK Configuration

### TypeScript SDK (sdk-ts)

#### CaptureConfig
| Option | Type | Default |
|--------|------|---------|
| `autoCaptureLevels` | `LogLevel[]` | `["ERROR", "FATAL"]` |
| `maxSerializationDepth` | `number` | `3` |
| `maxCollectionEntries` | `number` | `50` |
| `maxStringLength` | `number` | `4_096` (4 KB) |
| `maxPayloadBytes` | `number` | `262_144` (256 KB) |
| `maskingRules` | `RegExp[]` | `[/password/i, /token/i, /secret/i, /^sk_[a-zA-Z0-9]+$/]` |
| `denyFieldPatterns` | `RegExp[]` | `[]` |
| `allowFieldPatterns` | `RegExp[]` | `[]` |
| `manualCaptureReason` | `CaptureReason` | `"manual_trace"` |

#### BufferConfig
| Option | Type | Default |
|--------|------|---------|
| `maxMemoryMB` | `number` | `50` |
| `flushIntervalMs` | `number` | `2_000` (2 seconds) |
| `overflowStrategy` | `OverflowStrategy` | `"DROP_OLDEST"` |

#### AuthConfig (discriminated union)
| Mode | Fields |
|------|--------|
| `"none"` | (none) |
| `"apiKey"` | `apiKey: string` |
| `"bearer"` | `token: string` |
| `"mTLS"` | `clientCertificateAlias: string` |

#### ChronoTraceConfig
| Option | Type | Default |
|--------|------|---------|
| `appId` | `string` | (required, no default) |
| `environment` | `string` | `undefined` |
| `serviceName` | `string` | `undefined` |
| `serverUrl` | `string` | `undefined` |
| `auth` | `AuthConfig` | `undefined` |
| `runtime` | `RuntimeFlavor` | `"auto"` |
| `captureConfig` | `Partial<CaptureConfig>` | `undefined` |
| `bufferConfig` | `Partial<BufferConfig>` | `undefined` |
| `transport` | `ChronoTransport` | `undefined` |
| `contextManager` | `ContextManager` | `undefined` |
| `fetchImpl` | `typeof fetch` | `undefined` |
| `webSocketFactory` | `(url: string) => WebSocket` | `undefined` |
| `nodeProcess` | `NodeProcessLike` | `undefined` |
| `rules` | `RemoteRule[]` | `undefined` |

#### OverflowStrategy (type alias)
- `"DROP_OLDEST"`
- `"DROP_NEWEST"`
- `"BLOCK_CALLER"`

#### RuntimeFlavor (type alias)
- `"auto"`
- `"node"`
- `"browser"`

---

### Kotlin Multiplatform SDK (sdk-kmp)

#### CaptureConfig
| Option | Type | Default |
|--------|------|---------|
| `autoCaptureLevels` | `Set<LogLevel>` | `{LogLevel.ERROR, LogLevel.FATAL}` |
| `maxCollectionEntries` | `Int` | `50` |
| `maxStringLength` | `Int` | `4_096` |
| `maxPayloadBytes` | `Int` | `256 * 1024` (256 KB) |
| `maxSerializationDepth` | `Int` | `3` |
| `maskingKeys` | `List<Regex>` | `[Regex("password", IGNORE_CASE), Regex("token", IGNORE_CASE)]` |
| `maskingValues` | `List<Regex>` | `emptyList()` |
| `allowFieldPatterns` | `List<Regex>` | `emptyList()` |

#### BufferConfig
| Option | Type | Default |
|--------|------|---------|
| `maxEntries` | `Int` | `512` |
| `overflowStrategy` | `OverflowStrategy` | `DROP_OLDEST` |

#### ChronoConfig
| Option | Type | Default |
|--------|------|---------|
| `appId` | `String` | (required) |
| `serviceName` | `String` | (required) |
| `environment` | `String` | `"local"` |
| `sdkInstanceId` | `String` | `ChronoIds.nextId("sdk")` |
| `captureConfig` | `CaptureConfig` | `CaptureConfig()` |
| `bufferConfig` | `BufferConfig` | `BufferConfig()` |
| `transport` | `ChronoTransport` | `NoopTransport` |

#### OverflowStrategy (enum)
- `DROP_OLDEST`
- `DROP_NEWEST`

---

## Server Configuration

#### ChronoStoreOptions
| Option | Type | Default |
|--------|------|---------|
| `storageMode` | `StorageMode` | `FILE` |
| `dataDir` | `Path?` | `null` |
| `retentionDaysLogs` | `Long` | `30` |
| `retentionDaysSpans` | `Long` | `30` |
| `retentionDaysFrames` | `Long` | `7` |
| `clickHouse` | `ClickHouseConfig?` | `null` |
| `valkey` | `ValkeyConfig?` | `null` |
| `apiKeys` | `Set<String>` | `emptySet()` |
| `bearerTokens` | `Set<String>` | `emptySet()` |
| `keyMetadata` | `Map<String, ApiKeyMetadata>` | `emptyMap()` |

#### ClickHouseConfig
| Option | Type | Default |
|--------|------|---------|
| `jdbcUrl` | `String` | (required) |
| `database` | `String` | `"chronotrace"` |
| `username` | `String?` | `null` |
| `password` | `String?` | `null` |
| `connectTimeoutMs` | `Int` | `5_000` |
| `ingestQueueCapacity` | `Int` | `0` (sync writes) |
| `ingestQueueTimeoutMs` | `Long` | `5_000` |
| `asyncInsert` | `Boolean` | `false` |
| `bounceOnRejected` | `Boolean` | `true` |

#### ValkeyConfig
| Option | Type | Default |
|--------|------|---------|
| `host` | `String` | (required) |
| `port` | `Int` | `6379` |
| `database` | `Int` | `0` |
| `password` | `String?` | `null` |
| `keyPrefix` | `String` | `"chronotrace"` |

#### StorageMode (enum)
- `FILE`
- `CLICKHOUSE`

---

## Feature Flags

No explicit feature flag toggles were found in the scanned files.

However, the SDK uses a `RuntimeState` enum as a status indicator that effectively functions as a runtime health flag:

| State | Meaning |
|-------|---------|
| `CONNECTED` | Normal operation, transport healthy |
| `DEGRADED_BUFFERING` | Transport failed, buffering events locally |
| `RECONNECT_BACKOFF` | Attempting to reconnect to server |
| `LOCAL_FALLBACK` | Using NoopTransport, events dropped |
| `FATAL_FLUSH` | Fatal flush triggered, last resort |

---

## Constants

### Trace Header Format (ChronoTrace.kt)
- **traceparent header**: `"00-{traceId}-{spanId}-01"`
- **Chrono-Trace-Id**: Raw trace ID header
- **Chrono-Parent-Span-Id**: Raw parent span ID header

### ID Prefixes (ChronoIds.kt)
- `"log"` - log ID prefix
- `"span"` - span ID prefix
- `"trace"` - trace ID prefix
- `"sdk"` - SDK instance ID prefix

### Normalization
- Trace ID: filtered to alphanumeric, lowercased, padded/truncated to 32 chars
- Span ID: filtered to alphanumeric, lowercased, padded/truncated to 16 chars

---

## Environment Variable Mapping

Server configuration is loaded via Ktor's `application.conf` (HOCON). The following config properties map to environment/runtime variables:

| Config Property | Type | Default |
|----------------|------|---------|
| `chronotrace.authMode` | `String` | `"none"` |
| `chronotrace.storageMode` | `String` | `"FILE"` |
| `chronotrace.dataDir` | `String` | `null` |
| `chronotrace.retentionLogsDays` | `Long` | `30` |
| `chronotrace.retentionSpansDays` | `Long` | `30` |
| `chronotrace.retentionFramesDays` | `Long` | `7` |
| `chronotrace.apiKeys` | `String` (comma-separated) | `""` |
| `chronotrace.bearerTokens` | `String` (comma-separated) | `""` |
| `chronotrace.clickhouse.jdbcUrl` | `String` | `null` |
| `chronotrace.clickhouse.database` | `String` | `"chronotrace"` |
| `chronotrace.clickhouse.username` | `String` | `null` |
| `chronotrace.clickhouse.password` | `String` | `null` |
| `chronotrace.clickhouse.connectTimeoutMs` | `Int` | `5_000` |
| `chronotrace.valkey.host` | `String` | `null` |
| `chronotrace.valkey.port` | `Int` | `6379` |
| `chronotrace.valkey.database` | `Int` | `0` |
| `chronotrace.valkey.password` | `String` | `null` |
| `chronotrace.valkey.keyPrefix` | `String` | `"chronotrace"` |

### Auth Mode Mapping
| Mode | Auth Method |
|------|------------|
| `"none"` | No auth, all requests allowed |
| `"apiKey"` | `X-Api-Key` header required |
| `"bearer"` | `Authorization: Bearer <token>` header required |

---

## Notes

- The TypeScript SDK and Kotlin Multiplatform SDK share similar configuration structures but use slightly different property names and types (e.g., `maxMemoryMB` vs `maxEntries`, `OverflowStrategy` as string enum vs Kotlin enum).
- The server does not have a dedicated environment variable mapping section; it reads from `application.conf` which can be overridden via environment variables in deployment.
- No explicit feature flags (boolean toggles for optional features) were identified in the scanned codebase.