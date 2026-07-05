package com.paymentservice.ledger

import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Drives periodic epoch sealing. Single writer by design: one scheduler, one
 * service instance (leader election is a documented production gap), and the
 * epoch primary key plus the unique entry membership constraint make a
 * concurrent double-seal fail loudly instead of corrupting the chain.
 */
@Component
class AnchorBatch(private val anchorProcessor: AnchorProcessor) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${payment.anchor.interval-ms:300000}",
        initialDelayString = "\${payment.anchor.initial-delay-ms:60000}"
    )
    fun anchorTick() {
        try {
            anchorProcessor.anchorPending()
        } catch (e: DataAccessException) {
            log.error("Ledger anchoring failed", e)
        }
    }
}
