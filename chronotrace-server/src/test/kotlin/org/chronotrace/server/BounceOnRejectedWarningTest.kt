package org.chronotrace.server

import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Tests for the startup warning guard on bounceOnRejected=false.
 * When bounceOnRejected=false AND ingestQueueCapacity > 0, the server
 * prints a warning to stderr because events will be silently dropped
 * when the queue is full (no 503, no client retry triggered).
 */
class BounceOnRejectedWarningTest {

    /**
     * Flush on write OutputStream so System.err.println is captured immediately.
     */
    private class FlushOnWriteOutputStream(val delegate: OutputStream) : OutputStream() {
        override fun write(b: Int) {
            delegate.write(b)
            delegate.flush()
        }
        override fun write(b: ByteArray) {
            delegate.write(b)
            delegate.flush()
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            delegate.flush()
        }
        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }

    @Test
    fun warningPrintedToStderrWhenBounceOnRejectedFalseAndQueuePositive() {
        val baos = java.io.ByteArrayOutputStream()
        val flushStream = FlushOnWriteOutputStream(baos)
        val stderr = System.err

        System.setErr(java.io.PrintStream(flushStream, true))

        try {
            val options = ChronoStoreOptions(
                storageMode = StorageMode.CLICKHOUSE,
                clickHouse = ClickHouseConfig(
                    jdbcUrl = "jdbc:clickhouse://localhost:19999/default",
                    database = "chronotrace",
                    ingestQueueCapacity = 100,
                    bounceOnRejected = false,
                ),
                valkey = ValkeyConfig(host = "localhost", port = 19998),
            )
            // Create store in try block so init runs before use{}
            val store = ChronoStore(authMode = "none", options = options)
            store.use { }
        } finally {
            System.setErr(stderr)
        }

        val output = baos.toString()
        assertTrue(output.contains("WARNING"), "stderr should contain WARNING: $output")
        assertTrue(output.contains("bounceOnRejected=false"), "stderr should mention bounceOnRejected=false: $output")
        assertTrue(output.contains("ingestQueueCapacity=100"), "stderr should mention ingestQueueCapacity=100: $output")
        assertTrue(output.contains("silently drop events"), "stderr should mention silent drop: $output")
    }

    @Test
    fun noWarningWhenBounceOnRejectedTrue() {
        val baos = java.io.ByteArrayOutputStream()
        val flushStream = FlushOnWriteOutputStream(baos)
        val stderr = System.err

        System.setErr(java.io.PrintStream(flushStream, true))

        try {
            val options = ChronoStoreOptions(
                storageMode = StorageMode.CLICKHOUSE,
                clickHouse = ClickHouseConfig(
                    jdbcUrl = "jdbc:clickhouse://localhost:19999/default",
                    database = "chronotrace",
                    ingestQueueCapacity = 100,
                    bounceOnRejected = true,
                ),
                valkey = ValkeyConfig(host = "localhost", port = 19998),
            )
            val store = ChronoStore(authMode = "none", options = options)
            store.use { }
        } finally {
            System.setErr(stderr)
        }

        val output = baos.toString()
        assertTrue(!output.contains("bounceOnRejected"), "stderr should NOT mention bounceOnRejected when true: $output")
        assertTrue(!output.contains("silently drop"), "stderr should NOT mention silent drop when true: $output")
    }

    @Test
    fun noWarningWhenIngestQueueCapacityZero() {
        val baos = java.io.ByteArrayOutputStream()
        val flushStream = FlushOnWriteOutputStream(baos)
        val stderr = System.err

        System.setErr(java.io.PrintStream(flushStream, true))

        try {
            val options = ChronoStoreOptions(
                storageMode = StorageMode.CLICKHOUSE,
                clickHouse = ClickHouseConfig(
                    jdbcUrl = "jdbc:clickhouse://localhost:19999/default",
                    database = "chronotrace",
                    ingestQueueCapacity = 0,
                    bounceOnRejected = false,
                ),
                valkey = ValkeyConfig(host = "localhost", port = 19998),
            )
            val store = ChronoStore(authMode = "none", options = options)
            store.use { }
        } finally {
            System.setErr(stderr)
        }

        val output = baos.toString()
        assertTrue(!output.contains("bounceOnRejected"), "should NOT mention bounceOnRejected in sync mode: $output")
        assertTrue(!output.contains("silently drop"), "should NOT mention silent drop in sync mode: $output")
    }

    @Test
    fun noWarningWhenClickHouseConfigAbsent() {
        val baos = java.io.ByteArrayOutputStream()
        val flushStream = FlushOnWriteOutputStream(baos)
        val stderr = System.err

        System.setErr(java.io.PrintStream(flushStream, true))

        try {
            val options = ChronoStoreOptions(
                storageMode = StorageMode.FILE,
                clickHouse = null,
                dataDir = java.nio.file.Path.of("/tmp/chronotrace-test"),
            )
            val store = ChronoStore(authMode = "none", options = options)
            store.use { }
        } finally {
            System.setErr(stderr)
        }

        val output = baos.toString()
        assertTrue(!output.contains("bounceOnRejected"), "should NOT mention bounceOnRejected in FILE mode: $output")
        assertTrue(!output.contains("silently drop"), "should NOT mention silent drop in FILE mode: $output")
    }
}
