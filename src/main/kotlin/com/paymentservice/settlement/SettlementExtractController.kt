package com.paymentservice.settlement

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Exports the ledger movement extract as the CSV the offline Python `recon`
 * oracle parses: the ledger leg of the three-way reconciliation runbook.
 * Platform-level ops endpoint outside the API-key filter, like
 * /api/v1/reconciliation; restrict at the network layer in prod.
 */
@RestController
@RequestMapping("/api/v1/settlement-extract")
class SettlementExtractController(
    private val extractService: SettlementExtractService
) {

    @GetMapping(produces = ["text/csv"])
    fun extract(): String = extractService.toCsv(extractService.extract())
}
