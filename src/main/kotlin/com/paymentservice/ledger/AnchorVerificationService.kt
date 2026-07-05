package com.paymentservice.ledger

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * The online tamper check: recomputes every epoch root from the entries the
 * database holds NOW and every chain link from genesis, comparing both to the
 * stored anchors. Any UPDATE to an anchored entry (or to an anchor) since
 * sealing changes a recomputed value and surfaces here.
 *
 * Chain verification feeds each anchor's STORED chain hash into the next
 * link, so one corrupted anchor is flagged exactly once instead of cascading
 * a false failure over every later epoch. The independent Python verifier
 * (anchorverify) applies the same rule to the exported anchors.
 */
@Component
class AnchorVerificationService(
    private val anchorRepository: LedgerAnchorRepository,
    private val leafRepository: LedgerAnchorLeafRepository,
    private val ledgerRepository: LedgerRepository,
    meterRegistry: MeterRegistry,
    @Value("\${payment.anchor.lag-seconds:60}") private val lagSeconds: Long
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // 1 = last verification clean, 0 = tamper evidence or broken chain
    private val healthy = AtomicInteger(1)

    init {
        meterRegistry.gauge("ledger.anchor.healthy", healthy)
    }

    @Transactional(readOnly = true)
    fun verify(): AnchorVerificationReport {
        val anchors = anchorRepository.findAllByOrderByEpochAsc()
        val failures = mutableListOf<AnchorFailure>()
        var previousEpoch = 0L
        var previousChain = AnchorChain.GENESIS
        for (anchor in anchors) {
            failures += verifyAnchor(anchor, previousEpoch, previousChain)
            previousEpoch = anchor.epoch
            previousChain = anchor.chainHash
        }
        val unanchored = leafRepository.countUnanchored(Instant.now().minusSeconds(lagSeconds))
        val report = AnchorVerificationReport(
            verifiedEpochs = anchors.size,
            healthy = failures.isEmpty(),
            unanchoredEntries = unanchored,
            failures = failures
        )
        healthy.set(if (report.healthy) 1 else 0)
        if (!report.healthy) {
            log.error(
                "LEDGER_ANCHOR_ALERT tamper evidence: {} failing checks across {} epochs",
                failures.size, anchors.size
            )
        }
        return report
    }

    private fun verifyAnchor(
        anchor: LedgerAnchor,
        previousEpoch: Long,
        previousChain: String
    ): List<AnchorFailure> {
        val failures = mutableListOf<AnchorFailure>()
        if (anchor.epoch != previousEpoch + 1) {
            failures += AnchorFailure(
                anchor.epoch, AnchorFailureReason.EPOCH_GAP,
                (previousEpoch + 1).toString(), anchor.epoch.toString()
            )
        }
        val expectedChain = AnchorChain.next(previousChain, anchor.root, anchor.epoch)
        if (expectedChain != anchor.chainHash) {
            failures += AnchorFailure(
                anchor.epoch, AnchorFailureReason.CHAIN_MISMATCH, expectedChain, anchor.chainHash
            )
        }
        val leaves = leafRepository.findByEpochOrderByLeafIndexAsc(anchor.epoch)
        if (leaves.size != anchor.leafCount) {
            failures += AnchorFailure(
                anchor.epoch, AnchorFailureReason.LEAF_COUNT_MISMATCH,
                anchor.leafCount.toString(), leaves.size.toString()
            )
        }
        if (leaves.map { it.leafIndex } != leaves.indices.toList()) {
            failures += AnchorFailure(
                anchor.epoch, AnchorFailureReason.LEAF_INDEX_GAP,
                leaves.indices.joinToString(","), leaves.joinToString(",") { it.leafIndex.toString() }
            )
        }
        val recomputed = recomputeRoot(leaves)
        if (recomputed != anchor.root) {
            failures += AnchorFailure(
                anchor.epoch, AnchorFailureReason.ROOT_MISMATCH, anchor.root, recomputed
            )
        }
        return failures
    }

    private fun recomputeRoot(leaves: List<LedgerAnchorLeaf>): String {
        val entries = ledgerRepository.findAllById(leaves.map { it.entryId }).associateBy { it.id }
        return MerkleTree.rootHex(leaves.map { CanonicalLeafCodec.encode(entries.getValue(it.entryId)) })
    }
}
