package com.blackstore.application.pos

import com.blackstore.domain.pos.*
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.sales.SaleCommandFailure
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PosContextTest {
    private val client="11111111-1111-4111-8111-111111111111"
    private val q=OperationQuadruple(client,"device","sale","22222222-2222-4222-8222-222222222222")
    @Test fun onlyCanonicalCompleteValuesTranslateToAvailable() {
        val context=PosContextTranslator.fromWire(client,"device",1) as PosContextResult.Available
        assertEquals(client,context.context.clientInstanceId.toString())
        for (raw in listOf(null,"","bad","1-1-1-1-1")) assertEquals(PosContextResult.Unknown,PosContextTranslator.fromWire(raw,"device",1))
        for (raw in listOf(null,""," "," device","x".repeat(81))) assertEquals(PosContextResult.Unknown,PosContextTranslator.fromWire(client,raw,1))
        for (id in listOf(null,0L,-1L)) assertEquals(PosContextResult.Unknown,PosContextTranslator.fromWire(client,"device",id))
    }
    @Test fun validatorRequiresExactClientDeviceAndCashTerminalAndFailsClosed() {
        val result=PosContextTranslator.fromWire(client,"device",1)
        val step=PosContextValidationStep()
        assertNull(step.failure(result,q,1))
        assertEquals(SaleCommandFailure.Validation,step.failure(result,q.copy(deviceId="other"),1))
        assertEquals(SaleCommandFailure.Validation,step.failure(result,q.copy(clientInstanceId="33333333-3333-4333-8333-333333333333"),1))
        assertEquals(SaleCommandFailure.Validation,step.failure(result,q,2))
        assertEquals(SaleCommandFailure.Unavailable,step.failure(PosContextResult.Unavailable,q,1))
        assertEquals(SaleCommandFailure.Unavailable,step.failure(PosContextResult.Unknown,q,1))
    }
}
