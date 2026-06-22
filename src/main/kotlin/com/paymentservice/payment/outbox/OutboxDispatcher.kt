package com.paymentservice.payment.outbox

import com.paymentservice.payment.PaymentProviderPort
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Drains the outbox: for each PENDING event, dispatch() transitions the payment
 * to PENDING and marks the event in one transaction, then the provider is
 * contacted. Replaces the old fire-and-forget @Async from the controller, which
 * lost the provider call if the app died after the create commit.
 *
 * Residual window: a crash after dispatch() commits but before the provider
 * call lands leaves the payment in PENDING with no provider contact — caught by
 * the reconciliation stuck-PENDING sweep (Layer 3). The provider webhook is
 * idempotent, so any redelivery is safe.
 */
@Component
class OutboxDispatcher(
    private val outboxProcessor: OutboxProcessor,
    private val outboxRepository: OutboxEventRepository,
    private val provider: PaymentProviderPort
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${payment.outbox.dispatch-interval-ms:2000}",
        initialDelayString = "\${payment.outbox.initial-delay-ms:5000}"
    )
    fun dispatchPending() {
        val batch = outboxRepository.findDispatchable(BATCH_SIZE)
        for (event in batch) {
            try {
                val transactionId = outboxProcessor.dispatch(event.id) ?: continue
                provider.requestAuthorization(transactionId)
            } catch (e: Exception) {
                log.error("Outbox dispatch failed for event={}", event.id, e)
                outboxProcessor.recordFailure(event.id, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    companion object {
        const val BATCH_SIZE = 100
    }
}
