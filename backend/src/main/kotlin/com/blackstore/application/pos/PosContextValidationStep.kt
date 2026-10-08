package com.blackstore.application.pos

import com.blackstore.domain.pos.PosContextResult
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.sales.SaleCommandFailure

/** Runs only for a new Reserve, after current authority and historical replay. */
class PosContextValidationStep {
    fun failure(result: PosContextResult, identity: OperationQuadruple, cashTerminalId: Long): SaleCommandFailure? = when (result) {
        is PosContextResult.Available -> if (result.context.clientInstanceId.toString() != identity.clientInstanceId ||
            result.context.deviceId != identity.deviceId || result.context.terminalId != cashTerminalId) SaleCommandFailure.Validation else null
        PosContextResult.Unavailable, PosContextResult.Unknown -> SaleCommandFailure.Unavailable
    }
}
