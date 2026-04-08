# ChronoTrace

ChronoTrace is an AI-native temporal logging framework. This repository contains:

- `chronotrace-contract`: shared Kotlin data contracts used by the server.
- `chronotrace-server`: a Ktor server exposing ingest, query, remote rule, purge, and MCP endpoints.
- `sdk-kmp`: Kotlin Multiplatform SDK baseline.
- `sdk-ts`: TypeScript SDK baseline.

## Quick start

1. Run `./gradlew test`
2. Run `./gradlew :chronotrace-server:run`
3. Start the TypeScript SDK tests with `cd sdk-ts && npm test`

## Notes

- The server can run in in-memory, file-backed, or ClickHouse/Valkey-backed modes, so the repository is still runnable without external infrastructure.
- The public contracts are shaped to support the persistent backend without changing the SDK-facing API.
