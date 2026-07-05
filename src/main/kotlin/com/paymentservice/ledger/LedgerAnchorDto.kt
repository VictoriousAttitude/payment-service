package com.paymentservice.ledger

import java.time.Instant

class AnchorNotFoundException(epoch: Long) : RuntimeException("Ledger anchor epoch $epoch not found")

data class AnchorResponse(
    val epoch: Long,
    val root: String,
    val chainHash: String,
    val leafCount: Int,
    val createdAt: Instant
) {
    companion object {
        fun from(anchor: LedgerAnchor) = AnchorResponse(
            epoch = anchor.epoch,
            root = anchor.root,
            chainHash = anchor.chainHash,
            leafCount = anchor.leafCount,
            createdAt = anchor.createdAt
        )
    }
}

enum class AnchorFailureReason {
    EPOCH_GAP,
    CHAIN_MISMATCH,
    LEAF_COUNT_MISMATCH,
    LEAF_INDEX_GAP,
    ROOT_MISMATCH
}

data class AnchorFailure(
    val epoch: Long,
    val reason: AnchorFailureReason,
    val expected: String,
    val actual: String
)

/**
 * Result of recomputing every epoch root and chain link from the live ledger.
 * [unanchoredEntries] counts entries past the safety lag that no epoch covers
 * yet; persistently nonzero means the anchoring batch is not running.
 */
data class AnchorVerificationReport(
    val verifiedEpochs: Int,
    val healthy: Boolean,
    val unanchoredEntries: Long,
    val failures: List<AnchorFailure>
)
