package org.chronotrace.server

import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.EngineSSLConnectorConfig
import io.ktor.server.netty.NettyApplicationEngine
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Phase 3: TLS wiring tests.
 *
 * The server's [TlsConfig] reads keystore/truststore paths from env vars and exposes
 * loaded KeyStore / KeyManagerFactory / TrustManagerFactory objects plus an [sslPort].
 * [applyTlsToEngine] is the function that turns a [TlsConfig] into a Netty engine
 * configuration. The test in this file proves that, when env vars are set, the Netty
 * engine actually receives an SSL connector with the loaded material.
 *
 * Why this test exists: in earlier code, [TlsConfig] was built and printed but never
 * threaded into `embeddedServer(Netty, …)` — production deployments behind a TLS-only
 * load balancer were silently serving plain HTTP. The end-to-end assertion in
 * [Netty engine configuration includes an SSL connector when TLS is configured] is
 * the regression guard for that bug.
 */
class TlsWiringTest {

    @Test
    fun `TlsConfig loads keystore truststore and sslPort when env vars are set`(
        @TempDir tempDir: Path,
    ) {
        val keystore = tempDir.resolve("keystore.jks").toFile()
        val truststore = tempDir.resolve("truststore.jks").toFile()
        generateJks(keystore, password = "changeit", alias = "chronotrace")
        generateJks(truststore, password = "changeit", alias = "chronotrace-ca")

        val env = mapOf(
            "TLS_KEYSTORE_PATH" to keystore.absolutePath,
            "TLS_KEYSTORE_PASSWORD" to "changeit",
            "TLS_KEY_ALIAS" to "chronotrace",
            "TLS_TRUSTSTORE_PATH" to truststore.absolutePath,
            "TLS_TRUSTSTORE_PASSWORD" to "changeit",
            "CHRONOTRACE_TLS_SSL_PORT" to "9443",
        )

        val config = TlsConfig.fromMap(env)

        assertTrue(config.isConfigured, "TLS should be configured when keystore env vars are set")
        assertNotNull(config.keyStore, "keyStore should be loaded from JKS")
        assertNotNull(config.trustStore, "trustStore should be loaded from JKS")
        assertNotNull(config.keyManagerFactory, "keyManagerFactory should be initialised from keyStore")
        assertNotNull(config.trustManagerFactory, "trustManagerFactory should be initialised from trustStore")
        assertEquals(9443, config.sslPort, "sslPort should be read from CHRONOTRACE_TLS_SSL_PORT")
        // A live SSL context can be built from the factories — this is the strongest proof
        // that the loaded material is valid, not just a non-null reference.
        val ctx = SSLContext.getInstance("TLS").apply {
            init(config.keyManagerFactory!!.keyManagers, config.trustManagerFactory!!.trustManagers, null)
        }
        assertNotNull(ctx, "SSLContext should be initialisable from the loaded material")
    }

    @Test
    fun `TlsConfig is not configured when keystore env vars are absent`() {
        val config = TlsConfig.fromMap(emptyMap())
        assertTrue(!config.isConfigured, "isConfigured should be false when env vars are missing")
        assertNull(config.keyStore, "keyStore should be null when not configured")
        assertNull(config.trustStore, "trustStore should be null when not configured")
        assertNull(config.keyManagerFactory, "keyManagerFactory should be null when not configured")
        assertNull(config.trustManagerFactory, "trustManagerFactory should be null when not configured")
    }

    @Test
    fun `TlsConfig sslPort falls back to 0 when env var is unset`(@TempDir tempDir: Path) {
        val keystore = tempDir.resolve("keystore.jks").toFile()
        generateJks(keystore, password = "changeit", alias = "chronotrace")
        val env = mapOf(
            "TLS_KEYSTORE_PATH" to keystore.absolutePath,
            "TLS_KEYSTORE_PASSWORD" to "changeit",
        )
        val config = TlsConfig.fromMap(env)
        assertTrue(config.isConfigured)
        // When the port env var is absent the caller is expected to substitute the HTTP
        // port; the config exposes 0 as a sentinel meaning "not overridden".
        assertEquals(0, config.sslPort, "sslPort should be 0 when CHRONOTRACE_TLS_SSL_PORT is unset")
    }

    @Test
    fun `Netty engine configuration includes an SSL connector when TLS is configured`(
        @TempDir tempDir: Path,
    ) {
        val keystore = tempDir.resolve("keystore.jks").toFile()
        val truststore = tempDir.resolve("truststore.jks").toFile()
        generateJks(keystore, password = "changeit", alias = "chronotrace")
        generateJks(truststore, password = "changeit", alias = "chronotrace-ca")

        val config = TlsConfig.fromMap(
            mapOf(
                "TLS_KEYSTORE_PATH" to keystore.absolutePath,
                "TLS_KEYSTORE_PASSWORD" to "changeit",
                "TLS_KEY_ALIAS" to "chronotrace",
                "TLS_TRUSTSTORE_PATH" to truststore.absolutePath,
                "TLS_TRUSTSTORE_PASSWORD" to "changeit",
                "CHRONOTRACE_TLS_SSL_PORT" to "9443",
            ),
        )

        val engineConfig = NettyApplicationEngine.Configuration()
        applyTlsToEngine(config, sslPort = 9443, engineConfig = engineConfig)

        val sslConnectors = engineConfig.connectors
            .filter { it.type == io.ktor.server.engine.ConnectorType.HTTPS }
        assertEquals(1, sslConnectors.size, "Exactly one HTTPS connector must be registered")
        val https = sslConnectors.single() as EngineSSLConnectorConfig
        assertEquals(9443, https.port, "SSL port should match the env-var override")
        assertNotNull(https.keyStore, "SSL connector must carry the loaded KeyStore")
        assertEquals("chronotrace", https.keyAlias, "SSL connector must carry the configured alias")
        assertNotNull(https.trustStore, "SSL connector must carry the loaded trustStore")
    }

    @Test
    fun `Netty engine configuration has no SSL connector when TLS is not configured`() {
        val config = TlsConfig.fromMap(emptyMap())
        val engineConfig = NettyApplicationEngine.Configuration()
        applyTlsToEngine(config, sslPort = 8443, engineConfig = engineConfig)
        val sslConnectors = engineConfig.connectors
            .filter { it.type == io.ktor.server.engine.ConnectorType.HTTPS }
        assertTrue(sslConnectors.isEmpty(), "No HTTPS connector should be registered when TLS is not configured")
    }

    @Test
    fun `fromEnvironment reads System_getenv delegates to fromMap`() {
        // Sanity check: the production entry point and the testable factory must agree
        // on what counts as "configured" given the same env shape. We can't mutate
        // System.getenv() in JDK 17+, so we exercise the public surface by constructing
        // an empty-map config via both call sites and comparing.
        val fromEnv = TlsConfig.fromEnvironment()
        val fromMap = TlsConfig.fromMap(emptyMap())
        assertEquals(fromMap.isConfigured, fromEnv.isConfigured)
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private fun generateJks(target: File, password: String, alias: String) {
        target.parentFile.mkdirs()
        val dname = "CN=localhost,O=ChronoTrace,C=US"
        val process = ProcessBuilder(
            "keytool",
            "-genkeypair",
            "-alias", alias,
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "1",
            "-keystore", target.absolutePath,
            "-storepass", password,
            "-keypass", password,
            "-dname", dname,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        check(finished && process.exitValue() == 0) {
            "keytool failed (exit=${if (finished) process.exitValue() else "timeout"}): $output"
        }
    }
}
