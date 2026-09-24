package com.blackstore.domain.catalog

import com.blackstore.domain.exception.ForbiddenOperationException
import com.blackstore.domain.port.out.storecore.CatalogSnapshot
import com.blackstore.domain.port.out.storecore.ReserveLineCommand
import com.blackstore.domain.sales.TicketLine

/**
 * A reserve line for a SKU on the current projection uses that item's variant and price version.
 * A blank catalog price version keeps the cashier's version. Fixture rows without a price version stay as sent.
 */
class CatalogReserveLinePolicy {

    fun resolve(
        snapshot: CatalogSnapshot?,
        lines: List<ReserveLineCommand>,
        ticketLines: List<TicketLine>,
    ): List<ReserveLineCommand> {
        if (snapshot == null) return lines
        return lines.mapIndexed { index, line ->
            val rawSku = ticketLines.getOrNull(index)?.sku?.trim().orEmpty()
            val sku = rawSku.takeIf { it.isNotEmpty() && it != line.variantId }
            val item =
                when {
                    sku != null -> snapshot.items.firstOrNull { it.sku == sku }
                    else -> snapshot.items.firstOrNull { it.variantId == line.variantId }
                }
            if (sku != null && item == null) {
                throw ForbiddenOperationException("sku $sku is not on catalog ${snapshot.version}")
            }
            if (item == null) {
                line
            } else {
                ReserveLineCommand(
                    variantId = item.variantId,
                    quantity = line.quantity,
                    expectedPriceVersion = item.priceVersion?.takeIf { it.isNotBlank() } ?: line.expectedPriceVersion,
                )
            }
        }
    }
}
