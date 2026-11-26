package io.github.sidchi.payments.idempotency.postgres

import javax.sql.DataSource

/**
 * The `payment_idempotency_keys` DDL.
 *
 * Production deployments should apply [MIGRATION_RESOURCE] with their migration tool (it is named for Flyway and
 * works on the classpath as-is). [create] runs the same idempotent DDL directly, for tests and quick starts.
 */
public object PostgresIdempotencySchema {
    /** Classpath location of the migration, relative to the classpath root. */
    public const val MIGRATION_RESOURCE: String = "db/migration/V1__create_payment_idempotency_keys.sql"

    /** The migration SQL. */
    public val ddl: String by lazy {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream(MIGRATION_RESOURCE)) {
            "Missing classpath resource $MIGRATION_RESOURCE"
        }
        stream.bufferedReader().use { it.readText() }
    }

    /** Creates the table and index if they do not exist. Safe to call repeatedly. */
    public fun create(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute(ddl) }
        }
    }
}
