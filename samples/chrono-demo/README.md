# ChronoTrace Demo Application

A sample Kotlin JVM application demonstrating ChronoTrace's local variable capture instrumentation.

## Prerequisites

- Java 17+ (via jvmToolchain)
- Docker & Docker Compose (for server)
- Gradle 8.14.3

## Quick Start

```bash
# Start the server
docker compose up -d

# Wait for server healthy
curl http://localhost:8081/health

# Compile with ChronoTrace instrumentation
./gradlew compileKotlin --info 2>&1 | grep ChronoTrace

# Run the demo
./gradlew run
```

## What It Demonstrates

The demo includes 4 service classes using ChronoTrace APIs:

- **UserService** - `ChronoLogger.info()` with local variable capture (`userId`, `name`, `result`, `timestamp`)
- **OrderService** - `withSpan()` for nested span creation with context propagation
- **ProductService** - Multiple `ChronoLogger` calls at different levels (INFO, DEBUG)
- **DemoApplication** - Main entry point initializing `ChronoTrace.init()` and calling all services

## Expected Instrumentation Output

When compiling with the ChronoTrace plugin, you should see output like:

```
ChronoTrace: 4 functions instrumented, 8 locals captured
ChronoTrace:   demo/UserServiceKt → 2 fns, 4 locals captured
ChronoTrace:   demo/OrderServiceKt → 1 fns, 2 locals captured
ChronoTrace:   demo/ProductServiceKt → 1 fns, 2 locals captured
```

## Project Structure

```
samples/chrono-demo/
├── build.gradle.kts              # App build with ChronoTrace plugin
├── settings.gradle.kts           # Composite build for plugin
├── gradle.properties             # Plugin JAR path configuration
└── src/main/kotlin/demo/
    ├── DemoApplication.kt        # Main entry point
    ├── UserService.kt            # ChronoLogger with locals
    ├── OrderService.kt           # withSpan for spans
    └── ProductService.kt         # Multiple log levels
```

## Demo Script

For a complete end-to-end demo, use:

```bash
../../scripts/demo-run.sh
```

This will:
1. Start docker-compose services
2. Wait for server healthy
3. Compile with instrumentation visible
4. Run the demo
5. Query the server to verify data ingestion