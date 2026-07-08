package com.paymentservice.ledger

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Rolls the balance checkpoint forward. Each run folds every ledger delta in
 * (cursor, now - lag] into the per-account snapshots and advances the cursor,
 * all in one transaction so the checkpoint and the cursor move atomically.
 *
 * Correctness rests on the same commit-visibility safety window anchoring uses:
 * only entries older than lag-seconds are folded, so every entry at or before
 * the cursor is durably committed before it is summed. New entries always carry
 * created_at = now() > cursor, so they land in the live delta of a balance
 * read, never in the gap between snapshot and delta.
 *
 * Split from [SnapshotBatch] because @Transactional is proxy-based and would be
 * bypassed by self-invocation from the @Scheduled method.
 */
@Component
class SnapshotProcessor(
    private val ledgerRepository: LedgerRepository,
    private val snapshotRepository: BalanceSnapshotRepository,
    private val cursorRepository: SnapshotCursorRepository,
    @Value("\${payment.balance-snapshot.lag-seconds:60}") private val lagSeconds: Long
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun advance(): Int {
        val previous = cursorRepository.findById(CURSOR_ID).orElseThrow().asOf
        val cutoff = Instant.now().minusSeconds(lagSeconds)
        if (!cutoff.isAfter(previous)) {
            return 0
        }
        val deltas = ledgerRepository.aggregateBetween(previous, cutoff)
        deltas.forEach { d ->
            snapshotRepository.applyDelta(d.accountType.name, d.accountId, d.currency, d.totalDebits, d.totalCredits)
        }
        cursorRepository.save(SnapshotCursor(id = CURSOR_ID, asOf = cutoff))
        if (deltas.isNotEmpty()) {
            log.info("Folded {} balance deltas into snapshots up to {}", deltas.size, cutoff)
        }
        return deltas.size
    }

    companion object {
        const val CURSOR_ID: Short = 1
    }
}
