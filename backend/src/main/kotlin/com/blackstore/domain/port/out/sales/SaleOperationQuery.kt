package com.blackstore.domain.port.out.sales

import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.sales.SaleSaga

interface SaleOperationQuery {
    fun findSale(identity: OperationQuadruple): SaleSaga?
    fun resolveSale(operationId: String): SaleSaga?
}
