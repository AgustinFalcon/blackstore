package com.blackstore.infrastructure.sales

import com.blackstore.application.sales.DurableSaleCommandFlow
import com.blackstore.domain.port.out.sales.DurableSaleStore
import com.blackstore.domain.port.out.sales.SaleRecordStore
import com.blackstore.domain.port.out.storecore.StoreCoreInventoryPort
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

enum class CommercialWorkerMode {
    FIXTURE, LOOPBACK, UNKNOWN;
    companion object { fun fromWire(raw: String) = when (raw) { "fixture" -> FIXTURE; "loopback" -> LOOPBACK; else -> UNKNOWN } }
    fun permitted(enabled: Boolean, capability: Boolean, killSwitch: Boolean): Boolean = when (this) {
        FIXTURE -> true
        LOOPBACK -> enabled && capability && !killSwitch
        UNKNOWN -> false
    }
}

/** Fixture and explicitly enabled loopback only; unknown/live modes cannot claim or send. */
@Component
@ConditionalOnProperty(name = ["blackstore.persistence.enabled"], havingValue = "true")
class DurableSaleWorker(
    records: SaleRecordStore, inventory: StoreCoreInventoryPort,
    @Value("\${blackstore.storecore.integration.mode:fixture}") modeWire: String,
    @Value("\${blackstore.storecore.integration.enabled:false}") private val enabled: Boolean,
    @Value("\${blackstore.storecore.control.capability-active:false}") private val capability: Boolean,
    @Value("\${blackstore.storecore.control.kill-switch:true}") private val killSwitch: Boolean,
    @Value("\${blackstore.sales.worker.enabled:true}") private val workerEnabled: Boolean,
) {
    private val mode = CommercialWorkerMode.fromWire(modeWire)
    private val flow = DurableSaleCommandFlow(records as? DurableSaleStore ?: error("durable persistence unavailable"), inventory)
    @Scheduled(initialDelayString = "\${blackstore.sales.worker.initial-delay-ms:10000}", fixedDelayString = "\${blackstore.sales.worker.poll-ms:1000}")
    fun run() {
        if (!workerEnabled || !mode.permitted(enabled, capability, killSwitch)) return
        // One claim per tick bounds work; transaction and transport gates stay at their owning edges.
        flow.runNext()
    }
}
