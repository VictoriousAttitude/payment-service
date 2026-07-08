package com.paymentservice.ledger

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Drives periodic balance-snapshot folding. @SchedulerLock keeps a single node
 * folding per interval under multiple replicas. Even a crashed or duplicated
 * fold is harmless: the cursor and the additive upsert are transactional, so a
 * re-run folds only what the cursor has not yet covered, and reconciliation
 * re-derives the checkpoint from the ledger and flags any drift.
 */
@Component
class SnapshotBatch(private val snapshotProcessor: SnapshotProcessor) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${payment.balance-snapshot.interval-ms:300000}",
        initialDelayString = "\${payment.balance-snapshot.initial-delay-ms:60000}"
    )
    @SchedulerLock(name = "balance-snapshot")
    fun snapshotTick() {
        try {
            snapshotProcessor.advance()
        } catch (e: DataAccessException) {
            log.error("Balance snapshot fold failed", e)
        }
    }
}
