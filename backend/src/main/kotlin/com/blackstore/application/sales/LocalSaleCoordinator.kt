package com.blackstore.application.sales

import com.blackstore.domain.model.OperationQuadruple
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Component
class LocalSaleCoordinator {
    private data class SaleKey(val clientInstanceId: String, val deviceId: String, val saleId: String)
    private val guards = ConcurrentHashMap<SaleKey, ReentrantLock>()
    fun <T> coordinate(identity: OperationQuadruple, effect: () -> T): T =
        guards.computeIfAbsent(SaleKey(identity.clientInstanceId, identity.deviceId, identity.saleId)) { ReentrantLock() }.withLock(effect)
    internal fun isWaiting(identity: OperationQuadruple, thread: Thread): Boolean =
        guards[SaleKey(identity.clientInstanceId, identity.deviceId, identity.saleId)]?.hasQueuedThread(thread) == true
    companion object { val local = LocalSaleCoordinator() }
}
