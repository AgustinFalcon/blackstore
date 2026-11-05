package com.blackstore.infrastructure.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/** Physical PG16 commits exercise the deferred contract fence, not an in-memory substitute. */
class SaleAdmissionContractMigrationPostgresTest {
    private enum class ReceiptKind(val route: String) {
        Reserve("/api/v2/sales/reservations"),
        Commit("/api/v2/sales/{operationId}/commit"),
        Release("/api/v2/sales/{operationId}/release")
    }

    @Test fun cleanInstallHasNoInventedHistoricalMetadataAndValidates() = database(13) { source ->
        assertEquals("13", scalar(source,"SELECT max(version::integer) FROM flyway_schema_history WHERE success"))
        assertEquals("0", scalar(source,"SELECT count(*) FROM sale_command_admission_contracts"))
        flyway(source,13).validate()
    }

    @Test fun upgradeBackfillsOnlyProvenReceiptsWithoutChangingHistoricalFacts() = database(12) { source ->
        seed(source)
        val ids=ReceiptKind.entries.map { kind -> transaction(source) { c -> insertReceipt(c,kind) } }
        // An older intent has no admission provenance and must remain without a receipt.
        sql(source,"INSERT INTO sale_intents(client_instance_id,device_id,sale_id,operation_id,cash_session_id,created_by,contract_version,openapi_digest) VALUES('${UUID.randomUUID()}','legacy','legacy','${UUID.randomUUID()}',1,1,'legacy',repeat('a',64))")
        val before=history(source)
        flyway(source,13).migrate()
        assertEquals(before,history(source))
        assertEquals("3",scalar(source,"SELECT count(*) FROM sale_command_admission_contracts"))
        ids.zip(ReceiptKind.entries).forEach { (id,kind) ->
            assertEquals("sale-v2|sale-admission-v1|${kind.route}",scalar(source,"SELECT contract_family||'|'||fingerprint_version||'|'||canonical_route FROM sale_command_admission_contracts WHERE command_id='$id'"))
        }
        assertEquals("4",scalar(source,"SELECT count(*) FROM sale_intents"))
        assertEquals("3",scalar(source,"SELECT count(*) FROM sale_command_admission_receipts"))
        flyway(source,13).validate()
    }

    @Test fun physicalCommitRequiresMetadataAndAllowsBothExplicitContractFamilies() = database(13) { source ->
        seed(source)
        assertThrows(SQLException::class.java) { transaction(source) { insertReceipt(it,ReceiptKind.Reserve) } }
        assertEquals("0",scalar(source,"SELECT count(*) FROM sale_intents"))
        assertEquals("0",scalar(source,"SELECT count(*) FROM storecore_outbox_commands"))
        assertEquals("0",scalar(source,"SELECT count(*) FROM sale_command_admission_receipts"))
        transaction(source) { c ->
            c.createStatement().use { it.execute("SET LOCAL ROLE blackstore_app") }
            ReceiptKind.entries.forEach { kind -> metadata(c,insertReceipt(c,kind),"sale-v2","sale-admission-v1",kind.route) }
            metadata(c,insertReceipt(c,ReceiptKind.Reserve),"sale-v3","sale-admission-v2","/api/v3/sales/reservations")
        }
        assertEquals("4",scalar(source,"SELECT count(*) FROM sale_command_admission_contracts"))
        assertEquals("4",scalar(source,"SELECT count(*) FROM sale_command_admission_receipts"))
    }

    @Test fun unknownInconsistentDuplicateAndOrphanMetadataCannotCommit() = database(13) { source ->
        seed(source)
        val invalid=listOf(
            Triple("Future","sale-admission-v2","/api/v3/sales/reservations"),
            Triple("sale-v3","future","/api/v3/sales/reservations"),
            Triple("sale-v2","sale-admission-v2","/api/v3/sales/reservations"),
            Triple("sale-v3","sale-admission-v2","https://other.invalid/reservations"),
            Triple("sale-v2","sale-admission-v1",ReceiptKind.Commit.route)
        )
        invalid.forEach { (family,version,route) ->
            assertThrows(SQLException::class.java) { transaction(source) { c -> metadata(c,insertReceipt(c,ReceiptKind.Reserve),family,version,route) } }
        }
        assertThrows(SQLException::class.java) { transaction(source) { c -> metadata(c,insertReceipt(c,ReceiptKind.Commit),"sale-v3","sale-admission-v2","/api/v3/sales/reservations") } }
        assertThrows(SQLException::class.java) { transaction(source) { c -> metadata(c,UUID.randomUUID(),"sale-v2","sale-admission-v1",ReceiptKind.Reserve.route) } }
        assertThrows(SQLException::class.java) { transaction(source) { c ->
            val id=insertReceipt(c,ReceiptKind.Reserve)
            metadata(c,id,"sale-v2","sale-admission-v1",ReceiptKind.Reserve.route)
            metadata(c,id,"sale-v3","sale-admission-v2","/api/v3/sales/reservations")
        } }
        assertEquals("0",scalar(source,"SELECT count(*) FROM sale_command_admission_receipts"))
        assertEquals("0",scalar(source,"SELECT count(*) FROM sale_command_admission_contracts"))
    }

    @Test fun metadataHasMinimalGrantsAndIsImmutableEvenForMigrationOwner() = database(13) { source ->
        seed(source)
        transaction(source) { c -> metadata(c,insertReceipt(c,ReceiptKind.Reserve),"sale-v2","sale-admission-v1",ReceiptKind.Reserve.route) }
        for(privilege in listOf("SELECT","INSERT")) assertEquals("true",scalar(source,"SELECT has_table_privilege('blackstore_app','public.sale_command_admission_contracts','$privilege')::text"))
        for(privilege in listOf("UPDATE","DELETE","TRUNCATE","REFERENCES","TRIGGER")) assertEquals("false",scalar(source,"SELECT has_table_privilege('blackstore_app','public.sale_command_admission_contracts','$privilege')::text"))
        for(role in listOf("blackstore_projection_worker","blackstore_outbox_worker")) {
            for(privilege in listOf("SELECT","INSERT","UPDATE","DELETE","TRUNCATE")) assertEquals("false",scalar(source,"SELECT has_table_privilege('$role','public.sale_command_admission_contracts','$privilege')::text"))
        }
        assertEquals("true",scalar(source,"SELECT has_table_privilege('blackstore_auditor','public.sale_command_admission_contracts','SELECT')::text"))
        for(privilege in listOf("INSERT","UPDATE","DELETE","TRUNCATE")) assertEquals("false",scalar(source,"SELECT has_table_privilege('blackstore_auditor','public.sale_command_admission_contracts','$privilege')::text"))
        for(change in listOf("UPDATE public.sale_command_admission_contracts SET contract_family='sale-v2'","DELETE FROM public.sale_command_admission_contracts","TRUNCATE public.sale_command_admission_contracts")) assertThrows(SQLException::class.java) { sql(source,change) }
        assertEquals("1",scalar(source,"SELECT count(*) FROM sale_command_admission_contracts"))
    }

    private fun insertReceipt(c: Connection,kind: ReceiptKind): UUID {
        val command=UUID.randomUUID();val client=UUID.randomUUID();val operation=UUID.randomUUID()
        val intent=c.prepareStatement("INSERT INTO public.sale_intents(client_instance_id,device_id,sale_id,operation_id,cash_session_id,created_by,contract_version,openapi_digest) VALUES(?,'device','sale',?,1,1,'test',repeat('a',64)) RETURNING id").use { s ->
            s.setObject(1,client);s.setObject(2,operation);s.executeQuery().use { it.next();it.getLong(1) }
        }
        val payload="""{"clientInstanceId":"$client","deviceId":"device","saleId":"sale","operationId":"$operation","kind":"${kind.name.uppercase()}","canonicalPath":"/blackstore-integration/v1","contractVersion":"test","openapiDigest":"${"a".repeat(64)}"}"""
        val outbox=c.prepareStatement("INSERT INTO public.storecore_outbox_commands(client_instance_id,device_id,sale_id,operation_id,operation_kind,contract_version,openapi_digest,request_hash,payload_redacted,canonical_payload,payload_hash,actor_id,cash_session_id) VALUES(?,'device','sale',?,?,'test',repeat('a',64),repeat('b',64),'{}',?::jsonb,repeat('c',64),1,1) RETURNING id").use { s ->
            s.setObject(1,client);s.setObject(2,operation);s.setString(3,kind.name.uppercase());s.setString(4,payload);s.executeQuery().use { it.next();it.getLong(1) }
        }
        c.prepareStatement("INSERT INTO public.sale_command_admission_receipts(command_id,kind,payload_hash,actor_id,cash_session_id,client_instance_id,device_id,sale_id,operation_id,intent_id,outbox_id) VALUES(?,?,repeat('d',64),1,1,?,'device','sale',?,?,?)").use { s ->
            s.setObject(1,command);s.setString(2,kind.name);s.setObject(3,client);s.setObject(4,operation);s.setLong(5,intent);s.setLong(6,outbox);s.executeUpdate()
        }
        return command
    }
    private fun metadata(c: Connection,id: UUID,family: String,version: String,route: String) {
        c.prepareStatement("INSERT INTO public.sale_command_admission_contracts(command_id,contract_family,fingerprint_version,canonical_route) VALUES(?,?,?,?)").use { s ->
            s.setObject(1,id);s.setString(2,family);s.setString(3,version);s.setString(4,route);s.executeUpdate()
        }
    }
    private fun seed(source: DataSource) {
        sql(source,"INSERT INTO roles(code) VALUES('CASHIER')")
        sql(source,"INSERT INTO staff_users(login,password_hash,role_code,display_name) VALUES('contract-test','\$2fixture','CASHIER','Fixture')")
        sql(source,"INSERT INTO terminals(terminal_code) VALUES('contract-test')")
        sql(source,"INSERT INTO cash_session_projection(terminal_id,cashier_id,opened_at,opening_cash) VALUES(1,1,clock_timestamp(),0)")
        sql(source,"UPDATE accounting_runtime SET state='ACTIVE',accounting_activation_at=clock_timestamp()")
    }
    private fun history(source: DataSource)=listOf("sale_intents","sale_command_admission_receipts","storecore_outbox_commands","cash_ledger_events","payments").associateWith { table ->
        scalar(source,"SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text,'[]') FROM public.$table t")
    }
    private fun flyway(source: DataSource,target: Int)=Flyway.configure().dataSource(source).locations("classpath:db/migration").target(target.toString()).load()
    private fun database(target: Int,block: (DataSource)->Unit) {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { db ->
            db.start()
            val source=PGSimpleDataSource().also { it.setURL(db.jdbcUrl);it.user=db.username;it.password=db.password }
            flyway(source,target).migrate();block(source)
        }
    }
    private fun <T> transaction(source: DataSource,block: (Connection)->T): T=source.connection.use { c ->
        c.autoCommit=false
        try { val result=block(c);c.commit();result } catch(e: Exception) { c.rollback();throw e }
    }
    private fun sql(source: DataSource,statement: String) { source.connection.use { c -> c.createStatement().use { it.execute(statement) } } }
    private fun scalar(source: DataSource,statement: String): String=source.connection.use { c -> c.createStatement().use { s -> s.executeQuery(statement).use { it.next();it.getString(1) } } }
}
