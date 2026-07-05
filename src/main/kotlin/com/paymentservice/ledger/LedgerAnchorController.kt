package com.paymentservice.ledger

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Platform-level ops surface for the ledger anchor chain, like the
 * reconciliation and settlement-extract endpoints: outside the API-key filter
 * (it guards only /api/v1/payments and /api/v1/merchants), so restrict at the
 * network layer in prod. The anchors list and per-epoch leaf CSV are exactly
 * what the offline Python verifier consumes.
 */
@RestController
@RequestMapping("/api/v1/ledger-anchors")
class LedgerAnchorController(
    private val anchorRepository: LedgerAnchorRepository,
    private val leafRepository: LedgerAnchorLeafRepository,
    private val ledgerRepository: LedgerRepository,
    private val verificationService: AnchorVerificationService
) {

    @GetMapping
    fun list(): List<AnchorResponse> =
        anchorRepository.findAllByOrderByEpochAsc().map(AnchorResponse::from)

    @GetMapping("/verify")
    fun verify(): AnchorVerificationReport = verificationService.verify()

    @GetMapping("/{epoch}/leaves", produces = ["text/csv"])
    fun leaves(@PathVariable epoch: Long): String {
        val anchor = anchorRepository.findById(epoch).orElseThrow { AnchorNotFoundException(epoch) }
        val leaves = leafRepository.findByEpochOrderByLeafIndexAsc(anchor.epoch)
        val entries = ledgerRepository.findAllById(leaves.map { it.entryId }).associateBy { it.id }
        return AnchorLeafCsv.render(leaves.map { it to entries.getValue(it.entryId) })
    }
}
