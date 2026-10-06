package com.blackstore.infrastructure.identity

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager

@Testcontainers
class StaffIdentityMigrationTest {
    @Test fun v4FailureRollsBackToV3AndPreservesHistoricalForeignKeysOnRetry() {
        fun flyway(target: String?=null)=Flyway.configure().dataSource(postgres.jdbcUrl,postgres.username,postgres.password).also { if(target!=null) it.target(MigrationVersion.fromVersion(target)) }.load()
        flyway("3").migrate()
        DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password).use { c ->
            c.createStatement().execute("INSERT INTO roles VALUES ('CASHIER')")
            val cashier=c.prepareStatement("INSERT INTO staff_users(login,password_hash,role_code) VALUES ('cashier',?,'CASHIER') RETURNING id").use { s -> s.setString(1,"\$2a\$10\$012345678901234567890u"); s.executeQuery().use { r -> r.next(); r.getLong(1) } }
            val terminals=c.createStatement().executeQuery("INSERT INTO terminals(terminal_code) VALUES ('A'),('B') RETURNING id").use { r -> buildList { while(r.next()) add(r.getLong(1)) } }
            val cashIds=terminals.map { terminal -> c.prepareStatement("INSERT INTO cash_session_projection(terminal_id,cashier_id,opened_at,opening_cash) VALUES (?,?,now(),0) RETURNING id").use { s -> s.setLong(1,terminal); s.setLong(2,cashier); s.executeQuery().use { r -> r.next(); r.getLong(1) } } }
            assertThrows<Exception> { flyway().migrate() }
            assertEquals("3",flyway().info().current().version.version)
            c.createStatement().executeQuery("SELECT active FROM staff_users WHERE id=$cashier").use { r -> r.next(); assertTrue(r.getBoolean(1)) }
            c.createStatement().executeQuery("SELECT count(*) FROM information_schema.columns WHERE table_name='staff_users' AND column_name='display_name'").use { r -> r.next(); assertEquals(0,r.getInt(1)) }
            // Reconcile the duplicate in this disposable fixture, retaining both cash session IDs.
            c.prepareStatement("UPDATE cash_session_projection SET status='CLOSED',closed_at=now(),closing_cash_declared=0 WHERE id=?").use { s -> s.setLong(1,cashIds[1]); s.executeUpdate() }
            flyway().migrate(); flyway().validate()
            c.createStatement().executeQuery("SELECT id,active,display_name FROM staff_users WHERE login='cashier'").use { r -> r.next(); assertEquals(cashier,r.getLong(1)); assertFalse(r.getBoolean(2)); assertEquals("cashier",r.getString(3)) }
            c.createStatement().executeQuery("SELECT count(*) FROM cash_session_projection WHERE cashier_id=$cashier").use { r -> r.next(); assertEquals(2,r.getInt(1)) }
        }
    }
    companion object { @Container @JvmStatic val postgres=PostgreSQLContainer<Nothing>("postgres:16-alpine") }
}
