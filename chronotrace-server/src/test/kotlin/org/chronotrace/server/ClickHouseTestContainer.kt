package org.chronotrace.server

import org.testcontainers.clickhouse.ClickHouseContainer
import org.testcontainers.utility.DockerImageName

/**
 * Provides a ClickHouse test container for integration tests.
 *
 * Usage in tests:
 * ```
 * companion object {
 *     @Container
 *     val clickHouseContainer = ClickHouseTestContainer.newInstance()
 * }
 * ```
 *
 * For tests needing a database connection:
 * ```
 * val connection = clickHouseContainer.newJdbcConnection()
 * // use connection...
 * ```
 */
object ClickHouseTestContainer {
    private const val CLICKHOUSE_IMAGE = "clickhouse/clickhouse-server:24.11.1"

    /**
     * Creates a new ClickHouse container instance.
     * Container is configured with:
     * - Default ClickHouse port (8123)
     * - Default user: clickhouse/clickhouse
     * - Ready check via ping endpoint
     */
    fun newInstance(): ClickHouseContainer {
        return ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_IMAGE))
            .withExposedPorts(8123)
            .withUsername("clickhouse")
            .withPassword("clickhouse")
    }

    /**
     * Creates the test database schema.
     *
     * Usage:
     * ```kotlin
     * @BeforeEach
     * fun setUp() {
     *     ClickHouseTestContainer.createSchema(clickHouseContainer.jdbcUrl)
     * }
     * ```
     *
     * Note: This is a proof-of-concept. Actual schema creation should match
     * the server's schema migration approach.
     */
    fun createSchema(jdbcUrl: String) {
        // Schema creation would go here
        // For MVP, this documents the interface for future implementation
    }
}