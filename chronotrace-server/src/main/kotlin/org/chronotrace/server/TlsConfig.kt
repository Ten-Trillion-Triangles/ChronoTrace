package org.chronotrace.server

import java.io.File
import java.io.FileInputStream
import java.security.KeyStore

/**
 * TLS/HTTPS configuration for the ChronoTrace server.
 *
 * All fields are nullable — if [keystorePath] is null, the server starts in plain HTTP mode.
 * If [keystorePath] is set but [keyAlias] is null, defaults to "chronotrace".
 *
 * Environment variable mapping:
 * - [TLS_KEYSTORE_PATH][keystorePath]      → TLS_KEYSTORE_PATH
 * - [TLS_KEYSTORE_PASSWORD][keystorePassword] → TLS_KEYSTORE_PASSWORD
 * - [TLS_KEY_ALIAS][keyAlias]              → TLS_KEY_ALIAS
 * - [TLS_TRUSTSTORE_PATH][truststorePath] → TLS_TRUSTSTORE_PATH
 * - [TLS_TRUSTSTORE_PASSWORD][truststorePassword] → TLS_TRUSTSTORE_PASSWORD
 */
data class TlsConfig(
    val keystorePath: String?,
    val keystorePassword: String?,
    val keyAlias: String? = "chronotrace",
    val truststorePath: String? = null,
    val truststorePassword: String? = null,
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

    companion object {
        /**
         * Read TLS configuration from environment variables.
         *
         * Returns a [TlsConfig] with all fields populated from environment variables,
         * or with null fields if the corresponding environment variables are not set.
         * Even with null fields, the config object is returned — use [isConfigured]
         * to check whether HTTPS should be enabled.
         */
        fun fromEnvironment(): TlsConfig {
            return TlsConfig(
                keystorePath = System.getenv("TLS_KEYSTORE_PATH"),
                keystorePassword = System.getenv("TLS_KEYSTORE_PASSWORD"),
                keyAlias = System.getenv("TLS_KEY_ALIAS") ?: "chronotrace",
                truststorePath = System.getenv("TLS_TRUSTSTORE_PATH"),
                truststorePassword = System.getenv("TLS_TRUSTSTORE_PASSWORD"),
            )
        }
    }
}
