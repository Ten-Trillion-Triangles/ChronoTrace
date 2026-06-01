# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-05-27

### Added

- **chronotrace-server**: Production-ready Ktor server with:
  - WebSocket-based trace ingestion (`POST /api/v1/ingest`)
  - Log search with CEL filtering (`POST /api/v1/logs/search`)
  - Trace retrieval by traceId/spanId (`GET /api/v1/traces/{traceId}`)
  - MCP endpoint for AI coding agents (`POST /mcp`)
  - Prometheus metrics endpoint (`GET /metrics`)
  - Health endpoint with storage health check (`GET /health`)
  - ClickHouse storage backend with circuit breaker and bounded queue
  - File-based storage backend as alternative (no external dependencies)
  - Valkey caching layer for hot data
  - Configurable retention policies for logs, spans, and frames
  - API key and bearer token authentication

- **sdk-kmp**: Kotlin Multiplatform SDK with:
  - JVM OkHttp-based HTTP transport (production use)
  - WasmJs stub transport (no-op, for compilation compatibility)
  - Automatic instrumentation of functions annotated with `@ChronoTrace`
  - Local variable capture at any point in the call stack (frame snapshots)
  - In-process buffer with async flush to server
  - Retry with exponential backoff and jitter

- **sdk-ts**: TypeScript SDK with:
  - Browser and Node.js compatible HTTP transport
  - MCP client for direct server communication
  - Runtime health monitoring
  - Redaction framework for sensitive data
  - Remote rule evaluation (CEL expressions fetched from server)

- **chronotrace-kotlin-plugin**: Kotlin K2 compiler IR plugin that:
  - Injects frame snapshot instrumentation into compiled bytecode
  - Captures local variable values at any chosen stack frame
  - Filters out synthetic variables (prefixed with `$` or `<`)
  - Handles nested functions, lambdas, and local classes
  - Gracefully degrades when compiler API is unavailable

- **chronotrace-kotlin-plugin-gradle**: Gradle plugin that:
  - Applies the IR compiler plugin to user projects
  - Configures required compiler arguments automatically
  - Validates Kotlin version compatibility

- **chronotrace-contract**: Shared data schemas for:
  - Ingest payloads (logs, spans, frames)
  - Query requests and responses
  - MCP protocol types

- **Production deployment infrastructure**:
  - Docker Compose configuration for development
  - Production-ready Dockerfile with multi-stage build
  - Kubernetes manifests (Deployment, Service, ConfigMap, Secret)
  - TLS keystore configuration via environment variables
  - Comprehensive environment variable documentation

- **Operational runbook**: Coverage for:
  - ClickHouse connection failures
  - Quota exceeded incidents
  - Data retention issues
  - Performance degradation
  - High CPU/memory usage
  - Disk space exhaustion
  - WebSocket connection issues

### Changed

- Upgraded Kotlin compiler to 2.2.21 for K2 IR plugin compatibility
- Upgraded Ktor to 3.x Netty engine
- Migrated chronotrace-server to Kotlin DSL for build configuration
- All SDKs bumped to version 1.0.0

### Fixed

- Removed unused `sslConnector` call from ChronoTraceServer.kt (Ktor 3.x API incompatibility)
- Replaced broken `isCircuitBreakerOpen()` with `queueDepth()` in ChronoStore
- Fixed test classpath issue for Kotlin compiler plugin tests (moved `kotlin-compiler-embeddable` to testRuntimeOnly)
- Added ClickHouse storage health check to `/health` endpoint

### Security

- API key and bearer token authentication on all ingest/query endpoints
- Optional auth on health and metrics endpoints
- TLS keystore/truststore configuration via environment variables
- No credentials in configuration files (secrets via env vars and K8s secrets)

### Infrastructure

- Docker support for local development and production deployments
- Kubernetes manifests for production-grade deployments
- Health and readiness probes configured
- Resource limits and requests specified for all containers