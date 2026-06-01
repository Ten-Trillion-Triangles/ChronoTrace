package org.chronotrace.server

import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManagerFactory

/**
 * TLS/HTTPS configuration for the ChronoTrace server.
 *
 * All material fields are nullable — if [keystorePath] is null, the server starts in plain
 * HTTP mode. If [keystorePath] is set but [keyAlias] is null, defaults to "chronotrace".
 *
 * Environment variable mapping (see [fromMap] / [fromEnvironment]):
 * - [keystorePath]      → `TLS_KEYSTORE_PATH`
 * - [keystorePassword]  → `TLS_KEYSTORE_PASSWORD`
 * - [keyAlias]          → `TLS_KEY_ALIAS` (default `chronotrace`)
 * - [truststorePath]    → `TLS_TRUSTSTORE_PATH`
 * - [truststorePassword]→ `TLS_TRUSTSTORE_PASSWORD`
 * - [sslPort]           → `CHRONOTRACE_TLS_SSL_PORT` (0 = "use the HTTP port")
 */
data class TlsConfig(
    val keystorePath: String?,
    val keystorePassword: String?,
    val keyAlias: String? = "chronotrace",
    val truststorePath: String? = null,
    val truststorePassword: String? = null,
    /** HTTPS port. 0 means "not set" — the caller should substitute the HTTP port. */
    val sslPort: Int = 0,
) {
    /**
     * Whether TLS is configured and HTTPS should be enabled.
     */
    val isConfigured: Boolean get() = !keystorePath.isNullOrBlank() && !keystorePassword.isNullOrBlank()

    /**
     * The loaded JKS keystore, or null if TLS is not configured.
     */
    val keyStore: KeyStore? by lazy {
        if (!isConfigured) return@lazy null
        val path = keystorePath!!
        val pwd = keystorePassword!!
        KeyStore.getInstance("JKS").apply {
            FileInputStream(File(path)).use { fis -> load(fis, pwd.toCharArray()) }
        }
    }

    /**
     * The loaded truststore, or null if [truststorePath] is not set.
     */
    val trustStore: KeyStore? by lazy {
        if (truststorePath.isNullOrBlank()) return@lazy null
        val path = truststorePath!!
        val pwd = truststorePassword?.toCharArray() ?: "".toCharArray()
        KeyStore.getInstance("JKS").apply {
            FileInputStream(File(path)).use { fis -> load(fis, pwd) }
        }
    }

    /**
     * The KeyManagerFactory initialised from [keyStore], or null if TLS is not configured.
     * The factory is what the Netty engine actually consumes to build the SSL context.
     */
    val keyManagerFactory: KeyManagerFactory? by lazy {
        val ks = keyStore ?: return@lazy null
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(ks, keystorePassword?.toCharArray())
        }
    }

    /**
     * The TrustManagerFactory initialised from [trustStore], or null if no truststore was
     * configured. Null is fine for one-way TLS; mTLS deployments should set both
     * `TLS_TRUSTSTORE_PATH` and `TLS_TRUSTSTORE_PASSWORD`.
     */
    val trustManagerFactory: TrustManagerFactory? by lazy {
        val ts = trustStore ?: return@lazy null
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(ts)
        }
    }

    companion object {
        /**
         * Production entry point — reads TLS configuration from process environment.
         */
        fun fromEnvironment(): TlsConfig = fromMap(System.getenv())

        /**
         * Testable factory. Reads the same variable names as [fromEnvironment] but from a
         * caller-supplied map. This is the only function tests need to call — JDK 17+ does
         * not allow in-process mutation of [System.getenv], so the test layer cannot set
         * env vars on the live process and must inject them.
         */
        fun fromMap(env: Map<String, String?>): TlsConfig {
            return TlsConfig(
                keystorePath = env["TLS_KEYSTORE_PATH"],
                keystorePassword = env["TLS_KEYSTORE_PASSWORD"],
                keyAlias = env["TLS_KEY_ALIAS"] ?: "chronotrace",
                truststorePath = env["TLS_TRUSTSTORE_PATH"],
                truststorePassword = env["TLS_TRUSTSTORE_PASSWORD"],
                sslPort = env["CHRONOTRACE_TLS_SSL_PORT"]?.toIntOrNull() ?: 0,
            )
        }
    }
}
