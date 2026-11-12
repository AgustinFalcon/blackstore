package com.blackstore.infrastructure.persistence

import com.blackstore.domain.model.StoreCoreContractRef
import com.blackstore.domain.catalog.CatalogSnapshotValidator
import com.blackstore.domain.port.out.storecore.CatalogItem
import com.blackstore.domain.port.out.storecore.CatalogSnapshot
import com.blackstore.domain.port.out.storecore.CatalogSnapshotStore
import com.blackstore.domain.port.out.storecore.CatalogSource
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.sql.Connection
import java.sql.Timestamp
import java.sql.Types
import javax.sql.DataSource

@Component
@ConditionalOnProperty(name = ["blackstore.catalog-cache.enabled"], havingValue = "true")
class JdbcCatalogSnapshotStore(private val source: DataSource) : CatalogSnapshotStore {
    override fun replace(snapshot: CatalogSnapshot) {
        require(CatalogSnapshotValidator().isPersistable(snapshot)) { "only valid StoreCore live snapshots are cacheable" }
        source.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { it.execute("SET LOCAL ROLE blackstore_app") }
                connection.prepareStatement(
                    """INSERT INTO catalog_snapshot_cache(singleton,catalog_version,imported_at,valid_until,canonical_path,contract_version,contract_digest)
                    VALUES(TRUE,?,?,?,?,?,?) ON CONFLICT(singleton) DO UPDATE SET catalog_version=EXCLUDED.catalog_version,
                    imported_at=EXCLUDED.imported_at,valid_until=EXCLUDED.valid_until,canonical_path=EXCLUDED.canonical_path,
                    contract_version=EXCLUDED.contract_version,contract_digest=EXCLUDED.contract_digest,stored_at=clock_timestamp()""",
                ).use { statement ->
                    statement.setString(1, snapshot.version)
                    statement.setTimestamp(2, Timestamp.from(snapshot.importedAt))
                    snapshot.validUntil?.let { statement.setTimestamp(3, Timestamp.from(it)) } ?: statement.setNull(3, Types.TIMESTAMP_WITH_TIMEZONE)
                    statement.setString(4, snapshot.contract.canonicalPath)
                    statement.setString(5, snapshot.contract.contractVersion)
                    statement.setString(6, snapshot.contract.openapiDigestSha256)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM catalog_snapshot_items WHERE singleton=TRUE").use { it.executeUpdate() }
                writeItems(connection, CatalogProjectionKind.LEGACY, snapshot.items)
                writeItems(connection, CatalogProjectionKind.V2, snapshot.v2Items)
                connection.commit()
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            }
        }
    }

    override fun latest(): CatalogSnapshot? = source.connection.use { connection ->
        connection.autoCommit = false
            connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
        connection.isReadOnly = true
        try {
            connection.createStatement().use { it.execute("SET LOCAL ROLE blackstore_app") }
            var snapshot: CatalogSnapshot? = null
            connection.prepareStatement("SELECT * FROM catalog_snapshot_cache WHERE singleton=TRUE").use { header ->
                header.executeQuery().use { row ->
                    if (row.next()) {
                        val items = readItems(connection, CatalogProjectionKind.LEGACY)
                        val v2Items = readItems(connection, CatalogProjectionKind.V2)
                        snapshot = CatalogSnapshot(
                            version = row.getString("catalog_version"),
                            importedAt = row.getTimestamp("imported_at").toInstant(),
                            validUntil = row.getTimestamp("valid_until")?.toInstant(),
                            contract = StoreCoreContractRef(row.getString("canonical_path"), row.getString("contract_version"), row.getString("contract_digest")),
                            stale = true,
                            items = items,
                            v2Items = v2Items,
                            v2Stale = true,
                            source = CatalogSource.STORECORE_CACHE,
                        )
                    }
                }
            }
            connection.commit()
            snapshot
        } catch (failure: Exception) {
            connection.rollback()
            throw failure
        }
    }

    private fun writeItems(connection: Connection, projection: CatalogProjectionKind, items: List<CatalogItem>) {
        connection.prepareStatement(
            """INSERT INTO catalog_snapshot_items(singleton,projection_kind,ordinal,sku,product_name,variant_id,price_version,unit_price,
            raw_sku,raw_name,raw_variant_id,raw_price_version) VALUES(TRUE,?,?,?,?,?,?,?,?,?,?,?)""",
        ).use { statement ->
            items.forEachIndexed { index, item ->
                statement.setString(1, projection.wire)
                statement.setInt(2, index)
                statement.setString(3, item.sku)
                statement.setString(4, item.name)
                statement.setString(5, item.variantId)
                statement.setString(6, item.priceVersion)
                statement.setBigDecimal(7, item.unitPrice)
                statement.setString(8, item.rawSku)
                statement.setString(9, item.rawName)
                statement.setString(10, item.rawVariantId)
                statement.setString(11, item.rawPriceVersion)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun readItems(connection: Connection, projection: CatalogProjectionKind): List<CatalogItem> =
        connection.prepareStatement("""SELECT sku,product_name,variant_id,price_version,unit_price,raw_sku,raw_name,raw_variant_id,raw_price_version
            FROM catalog_snapshot_items WHERE singleton=TRUE AND projection_kind=? ORDER BY ordinal""").use { statement ->
            statement.setString(1, projection.wire)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(CatalogItem(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getBigDecimal(5),
                        rows.getString(6), rows.getString(7), rows.getString(8), rows.getString(9)))
                }
            }
        }
}

private enum class CatalogProjectionKind(val wire: String) {
    LEGACY("LEGACY"),
    V2("V2"),
}
