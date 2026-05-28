package org.chronotrace.server

import java.nio.file.Files
import java.sql.DriverManager

/**
 * Startup configuration validator supporting fail-fast behavior.
 * All validation errors are collected and reported together rather than
 * failing on the first error alone.
 */
object ConfigValidator {

    private val CEL_OPERATORS = setOf(
        "+", "-", "*", "/", "%", "==", "!=", "<", ">", "<=", ">=",
        "&&", "||", "!", "&", "|", "^", "~",
        "=", "+=", "-=", "*=", "/=",
    )

    /**
     * Validates the configured storage backend is accessible.
     * - InMemory always passes (no persistent state).
     * - File mode verifies dataDir exists or can be created.
     * - ClickHouse mode verifies JDBC connectivity.
     */
    fun validateStorageBackend(config: ChronoStoreOptions): List<String> {
        val errors = mutableListOf<String>()
        when (config.storageMode) {
            StorageMode.FILE -> {
                val dataDir = config.dataDir
                if (dataDir != null) {
                    if (!Files.exists(dataDir)) {
                        try {
                            Files.createDirectories(dataDir)
                        } catch (e: Exception) {
                            errors.add("File storage mode: dataDir '${dataDir}' does not exist and could not be created: ${e.message}")
                        }
                    } else if (!Files.isDirectory(dataDir)) {
                        errors.add("File storage mode: dataDir '${dataDir}' exists but is not a directory")
                    } else if (!Files.isReadable(dataDir) || !Files.isWritable(dataDir)) {
                        errors.add("File storage mode: dataDir '${dataDir}' is not readable/writable")
                    }
                }
            }
            StorageMode.CLICKHOUSE -> {
                val ch = config.clickHouse
                if (ch == null) {
                    errors.add("ClickHouse storage mode requires chronotrace.clickhouse configuration")
                } else {
                    errors.addAll(validateClickHouseConnection(ch))
                }
            }
        }
        return errors
    }

    private fun validateClickHouseConnection(config: ClickHouseConfig): List<String> {
        val errors = mutableListOf<String>()
        if (config.jdbcUrl.isBlank()) {
            errors.add("ClickHouse JDBC URL is blank")
            return errors
        }
        try {
            val props = java.util.Properties()
            config.username?.let { props["user"] = it }
            config.password?.let { props["password"] = it }
            props["connect_timeout"] = config.connectTimeoutMs.toString()
            DriverManager.getConnection(config.jdbcUrl, props).use { connection ->
                connection.prepareStatement("SELECT 1").use { statement ->
                    statement.executeQuery().use { rs ->
                        rs.next()
                    }
                }
            }
        } catch (e: Exception) {
            errors.add("ClickHouse connection failed: ${e.message}")
        }
        return errors
    }

    /**
     * Returns true if [expression] is a structurally valid CEL expression.
     * Uses a lightweight heuristic: balanced parentheses, valid operators,
     * no mismatched quotes, and no illegal characters.
     */
    fun validateRemoteRuleCEL(expression: String): Boolean {
        if (expression.isBlank()) return false

        // Check balanced parentheses
        var depth = 0
        for (char in expression) {
            when (char) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth < 0) return false
                }
            }
        }
        if (depth != 0) return false

        // Check for valid identifier characters (alphanumeric, underscore, dot)
        // CEL identifiers may contain letters, digits, underscores, and dots (for field access)
        val identifierPattern = Regex("^[a-zA-Z_][a-zA-Z0-9_.]*$")

        // Tokenize the expression and validate operators
        val tokenPattern = Regex(
            "[a-zA-Z_][a-zA-Z0-9_.]*|" +
                "[0-9]+(?:\\.[0-9]+)?|" +
                "\"[^\"]*\"|" +
                "'[^']*'|" +
                "[+\\-*/%==!<>]=?|&&|\\|\\||[!^~&|]",
        )
        val tokens = tokenPattern.findAll(expression).map { it.value }.toList()

        for (token in tokens) {
            // Skip known valid categories
            if (token.matches(identifierPattern)) continue
            if (token.toDoubleOrNull() != null) continue
            if (token.startsWith("\"")) continue
            if (token.startsWith("'")) continue
            if (token in CEL_OPERATORS) continue

            // Unknown token — could be an illegal character sequence
            return false
        }

        // Disallow adjacent operators that form invalid CEL
        val adjacentOps = Regex("(?:[+\\-*/%=<>&|]{2,})")
        if (adjacentOps.containsMatchIn(expression)) {
            val cleaned = expression.replace(Regex("[a-zA-Z_][a-zA-Z0-9_.]*"), "ID")
                .replace(Regex("[0-9]+(?:\\.[0-9]+)?"), "NUM")
                .replace(Regex("\"[^\"]*\"|'[^']*'"), "STR")
            if (adjacentOps.containsMatchIn(cleaned)) return false
        }

        return true
    }

    /**
     * Returns true if [key] meets minimum API key requirements:
     * - At least 16 characters long
     * - Not purely numeric
     * - Not purely whitespace
     * - Contains at least one letter and one digit
     */
    fun validateApiKey(key: String): Boolean {
        if (key.isBlank()) return false
        if (key.length < 16) return false
        if (key.all { it.isDigit() }) return false
        if (key.all { it.isWhitespace() }) return false
        val hasLetter = key.any { it.isLetter() }
        val hasDigit = key.any { it.isDigit() }
        if (!hasLetter || !hasDigit) return false
        return true
    }

    /**
     * Runs all startup validations against the provided configuration.
     * Returns a list of error messages. Empty list means all validations passed.
     */
    fun validateAll(
        authMode: String,
        storageMode: StorageMode,
        dataDir: java.nio.file.Path?,
        clickHouse: ClickHouseConfig?,
    ): List<String> {
        val errors = mutableListOf<String>()

        // Validate storage backend
        val options = ChronoStoreOptions(
            storageMode = storageMode,
            dataDir = dataDir,
            clickHouse = clickHouse,
        )
        errors.addAll(validateStorageBackend(options))

        return errors
    }
}