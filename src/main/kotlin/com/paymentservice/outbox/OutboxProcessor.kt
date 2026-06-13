package com.paymentservice.outbox

import com.paymentservice.payment.PaymentStatus
import com.paymentservice.payment.TransactionNotFoundException
import com.paymentservice.payment.TransactionRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Per-event transactional unit of work. Separate bean from the dispatcher so
 * each event commits in its own transaction (the @Transactional proxy only
 * applies across bean boundaries, not on self-invocation).
 */
@Component
class OutboxProcessor(
    private val outboxRepository: OutboxEventRepository,
    private val transactionRepository: TransactionRepository
) {

    /**
     * Atomically transitions the payment CREATED -> PENDING and marks the event
     * dispatched. Idempotent: a redelivered event whose payment already left
     * CREATED only flips the event row. Returns the aggregate id to contact the
     * provider for, or null if the event was already handled.
     */
    @Transactional
    fun dispatch(eventId: UUID): UUID? {
        val event = outboxRepository.findById(eventId).orElse(null) ?: return null
        if (event.status != OutboxStatus.PENDING) return null

        val transaction = transactionRepository.findById(event.aggregateId)
            .orElseThrow { TransactionNotFoundException(event.aggregateId) }

        if (transaction.status == PaymentStatus.CREATED) {
            transaction.transitionTo(PaymentStatus.PENDING)
            transactionRepository.save(transaction)
        }

        event.markDispatched()
        outboxRepository.save(event)
        return transaction.id
    }

    @Transactional
    fun recordFailure(eventId: UUID, error: String) {
        val event = outboxRepository.findById(eventId).orElse(null) ?: return
        event.recordFailure(error, MAX_ATTEMPTS)
        outboxRepository.save(event)
    }

    companion object {
        const val MAX_ATTEMPTS = 5
    }
}
