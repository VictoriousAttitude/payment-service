package com.paymentservice.expiry

import com.paymentservice.payment.PaymentStatus
import com.paymentservice.payment.TransactionEvent
import com.paymentservice.payment.TransactionEventRepository
import com.paymentservice.payment.TransactionRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Per-authorization expiry unit of work. Separate bean from the batch so each
 * expires in its own transaction (the @Transactional proxy only applies across
 * bean boundaries, not on self-invocation) — one stuck row can't poison the
 * rest of the sweep.
 *
 * An authorization is a time-bounded hold. If it is never captured within the
 * validity window it must be released; this records the AUTHORIZED -> EXPIRED
 * transition the rest of the system keys on. No ledger movement: nothing was
 * ever posted for a bare authorization.
 */
@Component
class AuthorizationExpiryProcessor(
    private val transactionRepository: TransactionRepository,
    private val transactionEventRepository: TransactionEventRepository
) {

    /**
     * Transitions AUTHORIZED -> EXPIRED. Idempotent: a row that already left
     * AUTHORIZED (captured or voided in the meantime) is skipped. Returns the
     * currency on success for metric tagging, null if nothing was done. The
     * @Version column rejects a concurrent writer's stale update.
     */
    @Transactional
    fun expire(transactionId: UUID): String? {
        val transaction = transactionRepository.findById(transactionId).orElse(null) ?: return null
        if (transaction.status != PaymentStatus.AUTHORIZED) return null

        transaction.transitionTo(PaymentStatus.EXPIRED)
        transactionRepository.save(transaction)
        transactionEventRepository.save(
            TransactionEvent(
                transactionId = transaction.id,
                fromStatus = PaymentStatus.AUTHORIZED,
                toStatus = PaymentStatus.EXPIRED
            )
        )
        return transaction.currency
    }
}
