package com.blackstore.infrastructure.persistence

import org.flywaydb.core.Flyway
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.PrintWriter
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.logging.Logger
import javax.sql.DataSource

@Configuration
@ConditionalOnProperty(name = ["blackstore.persistence.enabled"], havingValue = "true")
class BlackStorePersistenceConfig(
    @Value("\${blackstore.persistence.url}") private val url: String,
    @Value("\${blackstore.persistence.username}") private val username: String,
    @Value("\${blackstore.persistence.password}") private val password: String,
    @Value("\${blackstore.persistence.staff-identity-backup-ref:}") private val identityBackupRef: String = "",
) {
    @Bean
    fun blackStoreDataSource(): DataSource = SimpleConnectionDataSource(url, username, password)

    @Bean
    fun localDatabaseSeed(): LocalDatabaseSeed = LocalDatabaseSeed()

    @Bean
    fun migrateAndSeed(dataSource: DataSource, seed: LocalDatabaseSeed): ApplicationRunner =
        ApplicationRunner {
            Class.forName("org.postgresql.Driver")
            val flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load()
            val existingVersion = flyway.info().current()?.version?.version?.toIntOrNull()
            require(existingVersion == null || existingVersion >= 4 || identityBackupRef.isNotBlank()) {
                "V4 requires a verified backup/restore evidence reference: blackstore.persistence.staff-identity-backup-ref"
            }
            flyway.migrate()
            flyway.validate()
            dataSource.connection.use { connection ->
                connection.createStatement().execute(
                    """
                    INSERT INTO roles (code) VALUES ('CASHIER'), ('SUPERVISOR'), ('OWNER'), ('AUDITOR')
                    ON CONFLICT DO NOTHING
                    """.trimIndent(),
                )
                connection.createStatement().execute(
                    """
                    INSERT INTO companion_installation (singleton, storecore_installation_ref, entitlement_state, environment, service_client_ref)
                    VALUES (TRUE, '11111111-1111-1111-1111-111111111111', 'ENABLED', 'TEST', 'blackstore-service-local')
                    ON CONFLICT DO NOTHING
                    """.trimIndent(),
                )
                connection.createStatement().execute(
                    """
                    INSERT INTO staff_users (login, display_name, password_hash, role_code, active)
                    VALUES ('cashier', 'Historical cashier', '${'$'}2a${'$'}10${'$'}012345678901234567890u', 'CASHIER', FALSE)
                    ON CONFLICT (login) DO NOTHING
                    """.trimIndent(),
                )
                connection.createStatement().execute(
                    "INSERT INTO terminals (terminal_code) VALUES ('T-1') ON CONFLICT (terminal_code) DO NOTHING",
                )
                connection.createStatement().executeQuery(
                    "SELECT id FROM staff_users WHERE login = 'cashier'",
                ).use { rows ->
                    rows.next()
                    seed.cashierId = rows.getLong(1)
                }
                connection.createStatement().executeQuery(
                    "SELECT id FROM terminals WHERE terminal_code = 'T-1'",
                ).use { rows ->
                    rows.next()
                    seed.terminalId = rows.getLong(1)
                }
            }
        }
}

class LocalDatabaseSeed {
    var terminalId: Long = 0
    var cashierId: Long = 0
}

private class SimpleConnectionDataSource(
    private val url: String,
    private val username: String,
    private val password: String,
) : DataSource {
    override fun getConnection(): Connection = DriverManager.getConnection(url, username, password)

    override fun getConnection(username: String, password: String): Connection =
        DriverManager.getConnection(url, username, password)

    override fun getLogWriter(): PrintWriter? = null

    override fun setLogWriter(out: PrintWriter?) = Unit

    override fun setLoginTimeout(seconds: Int) = Unit

    override fun getLoginTimeout(): Int = 0

    override fun getParentLogger(): Logger = Logger.getLogger("blackstore.persistence")

    override fun <T : Any> unwrap(iface: Class<T>): T =
        if (iface.isInstance(this)) iface.cast(this) else throw SQLException("not a $iface")

    override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)
}
