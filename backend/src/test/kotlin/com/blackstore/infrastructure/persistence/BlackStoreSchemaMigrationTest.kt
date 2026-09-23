package com.blackstore.infrastructure.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.io.path.readText

@Testcontainers
class BlackStoreSchemaMigrationTest {

    @Test
    fun migrationScriptHasNoCrossDatabaseAccess() {
        val sql = Path.of("src/main/resources/db/migration/V1__blackstore_schema.sql").readText().lowercase()
        assertFalse(sql.contains("dblink"))
        assertFalse(sql.contains("postgres_fdw"))
        assertFalse(sql.contains("jdbc:"))
        assertFalse(sql.contains("create extension"))
    }

    @Test
    fun flywayMigratesAndHistoricalTablesAreAppendOnly() {
        val flyway =
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration")
                .load()
        val result = flyway.migrate()
        assertTrue(result.migrationsExecuted >= 1)

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                val foreignKeys =
                    statement.executeQuery(
                        """
                        SELECT COUNT(*)
                        FROM pg_constraint c
                        JOIN pg_class rel ON rel.oid = c.conrelid
                        JOIN pg_namespace source_ns ON source_ns.oid = rel.relnamespace
                        JOIN pg_class target ON target.oid = c.confrelid
                        JOIN pg_namespace target_ns ON target_ns.oid = target.relnamespace
                        WHERE c.contype = 'f'
                          AND (source_ns.nspname <> 'public' OR target_ns.nspname <> 'public')
                        """.trimIndent(),
                    )
                foreignKeys.next()
                assertEquals(0, foreignKeys.getInt(1))
            }

            connection.createStatement().execute("SET ROLE blackstore_app")
            connection.createStatement().execute(
                """
                INSERT INTO audit_events (event_type, aggregate_type, payload_redacted)
                VALUES ('SCHEMA_SMOKE', 'audit', '{"source":"task-003"}'::jsonb)
                """.trimIndent(),
            )
            assertThrows<SQLException> {
                connection.createStatement().execute("UPDATE audit_events SET event_type = 'MUTATED'")
            }
            assertThrows<SQLException> {
                connection.createStatement().execute("DELETE FROM audit_events")
            }
        }
    }

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }
}
