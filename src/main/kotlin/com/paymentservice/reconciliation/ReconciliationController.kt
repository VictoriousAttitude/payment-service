package com.paymentservice.reconciliation

import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/reconciliation")
class ReconciliationController(
    private val reconciliationService: ReconciliationService
) {

    @GetMapping
    fun runReconciliation(): ReconciliationReport {
        return reconciliationService.runFullReconciliation()
    }
}
