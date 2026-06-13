package com.paymentservice.settlement

import com.paymentservice.payment.PaymentStatus
import com.paymentservice.payment.TransactionEvent
import com.paymentservice.payment.TransactionEventRepository
import com.paymentservice.payment.TransactionRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Per-transaction settlement unit of work. Separate bean from the batch so each
 * settles in its own transaction (the @Transactional proxy only applies across
 * bean boundaries, not on self-invocation).
 *
 * Settlement is the lifecycle milestone marking funds as cleared with the
 * acquirer (T+N), not a money movement here: the merchant credit was already
 * recognized in the ledger at capture. A fuller model would post clearing
 * entries from a holding account; this records the state transition the rest of
 * the system (reconciliation, reporting) keys on.
 */
@Component
class SettlementProcessor(
    private val transactionRepository: TransactionRepository,
    private val transactionEventRepository: TransactionEventRepository
) {

    /**
     * Transitions CAPTURED -> SETTLED. Idempotent: a row that already left
     * CAPTURED (settled by a prior run, or refunded) is skipped. Returns the
     * currency on success for metric tagging, null if nothing was done. The
     * @Version column rejects a concurrent writer's stale update.
     */
    @Transactional
    fun settle(transactionId: UUID): String? {
        val transaction = transactionRepository.findById(transactionId).orElse(null) ?: return null
        if (transaction.status != PaymentStatus.CAPTURED) return null

        transaction.transitionTo(PaymentStatus.SETTLED)
        transactionRepository.save(transaction)
        transactionEventRepository.save(
            TransactionEvent(
                transactionId = transaction.id,
                fromStatus = PaymentStatus.CAPTURED,
                toStatus = PaymentStatus.SETTLED
            )
        )
        return transaction.currency
    }
}
