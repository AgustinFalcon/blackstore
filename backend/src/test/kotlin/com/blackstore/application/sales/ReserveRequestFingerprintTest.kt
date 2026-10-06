package com.blackstore.application.sales

import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.port.out.storecore.ReserveLineCommand
import com.blackstore.domain.sales.TicketLine
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ReserveRequestFingerprintTest {
    private val identity=OperationQuadruple("client","device","sale","operation")
    private val wire=listOf(ReserveLineCommand("submitted-variant",1,"submitted-price"))
    private fun ticket(price: String)=listOf(TicketLine("SKU","Item",1,BigDecimal(price),BigDecimal.ZERO))
    @Test fun moneyScaleDoesNotChangeAdmissionFingerprint() {
        assertEquals(ReserveRequestFingerprint.hash(identity,1,wire,ticket("10"),null),ReserveRequestFingerprint.hash(identity,1,wire,ticket("10.00"),null))
        assertNotEquals(ReserveRequestFingerprint.hash(identity,1,wire,ticket("10"),null),ReserveRequestFingerprint.hash(identity,1,wire,ticket("11"),null))
    }
    @Test fun wireEvidenceIsIndependentOfCatalogNormalizedDispatch() {
        val submitted=ReserveRequestFingerprint.hash(identity,1,wire,ticket("10"),"override")
        val canonical=listOf(ReserveLineCommand("catalog-variant",1,"catalog-price"))
        assertNotEquals(submitted,ReserveRequestFingerprint.hash(identity,1,canonical,ticket("10"),"override"))
        assertEquals(submitted,ReserveRequestFingerprint.hash(identity,1,wire,ticket("10.00"),"override"))
        assertNotEquals(submitted,ReserveRequestFingerprint.hash(identity,1,wire,ticket("10"),null))
    }
}
