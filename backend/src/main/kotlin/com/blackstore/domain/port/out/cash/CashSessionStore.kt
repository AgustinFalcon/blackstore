package com.blackstore.domain.port.out.cash

import com.blackstore.domain.cash.CashSession

interface CashSessionStore {
    fun list(): List<CashSession>
}
