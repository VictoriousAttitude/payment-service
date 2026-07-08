package com.paymentservice.ledger

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Drives periodic epoch sealing. Single writer across the cluster: @SchedulerLock
 * ensures one node seals per interval even under multiple replicas, and the epoch
 * primary key plus the unique entry membership constraint make a concurrent
 * double-seal fail loudly instead of corrupting the chain.
 */
@Component
class AnchorBatch(private val anchorProcessor: AnchorProcessor) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${payment.anchor.interval-ms:300000}",
        initialDelayString = "\${payment.anchor.initial-delay-ms:60000}"
    )
    @SchedulerLock(name = "anchor")
    fun anchorTick() {
        try {
            anchorProcessor.anchorPending()
        } catch (e: DataAccessException) {
            log.error("Ledger anchoring failed", e)
        }
    }
}
