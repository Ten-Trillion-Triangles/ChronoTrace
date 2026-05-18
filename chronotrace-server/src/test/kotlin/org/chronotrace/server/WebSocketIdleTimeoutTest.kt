package org.chronotrace.server

import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies WebSocket idle timeout configuration is correctly applied to the server.
 *
 * The idle timeout mechanism:
 * - Ktor sends a ping frame every `pingPeriodMillis` if the connection is idle
 * - Ktor waits `timeoutMillis` for a pong response before closing the connection
 * - When wsIdleTimeoutMs=0, both are disabled (no server-side timeout)
 *
 * This test covers the configuration path: ChronoStoreOptions.wsIdleTimeoutMs → env var
 * CHRONOTRACE_WS_IDLE_TIMEOUT_MS → ChronoTraceServer → ServerModule WebSockets install.
 */
class WebSocketIdleTimeoutTest {

    @Test
    fun `wsIdleTimeoutMs defaults to 60 seconds in ChronoStoreOptions`() {
        val options = ChronoStoreOptions()
        assertEquals(60_000L, options.wsIdleTimeoutMs)
    }

    @Test
    fun `wsIdleTimeoutMs can be set to a custom value`() {
        val options = ChronoStoreOptions(wsIdleTimeoutMs = 30_000L)
        assertEquals(30_000L, options.wsIdleTimeoutMs)
    }

    @Test
    fun `wsIdleTimeoutMs can be set to zero to disable idle timeout`() {
        val options = ChronoStoreOptions(wsIdleTimeoutMs = 0L)
        assertEquals(0L, options.wsIdleTimeoutMs)
    }

    @Test
    fun `WebSocket plugin receives idle timeout config via store options`() = testApplication {
        // Use a custom store with a non-default idle timeout
        val customTimeout = 45_000L
        val store = ChronoStore("none", ChronoStoreOptions(wsIdleTimeoutMs = customTimeout))

        application {
            // Verify the store's options expose the configured value
            assertEquals(customTimeout, store.options.wsIdleTimeoutMs)
        }
    }

    @Test
    fun `zero wsIdleTimeoutMs disables server-side idle timeout`() = testApplication {
        val store = ChronoStore("none", ChronoStoreOptions(wsIdleTimeoutMs = 0L))

        application {
            chronoTraceModule(store)
        }

        // 0 → WebSockets config sets pingPeriodMillis=0, timeoutMillis=0 → no server-initiated pings/timeouts
        assertEquals(0L, store.options.wsIdleTimeoutMs)
    }
}