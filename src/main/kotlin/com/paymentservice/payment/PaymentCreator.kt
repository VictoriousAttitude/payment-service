package com.paymentservice.payment

import com.paymentservice.payment.outbox.OutboxEvent
import com.paymentservice.payment.outbox.OutboxEventRepository
import com.paymentservice.payment.dto.CreatePaymentRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Persists a new payment and its provider-dispatch outbox event in one
 * transaction. Separate bean so the write is genuinely transactional —
 * PaymentService.createPayment is deliberately non-transactional to keep the
 * idempotency-race recovery re-fetch on a clean connection, and a self-invoked
 * @Transactional method would not get a proxy anyway.
 *
 * On idempotency-key collision the unique constraint trips at flush; both the
 * transaction and the outbox insert roll back together (no orphan event), and
 * the violation propagates for the caller to recover.
 */
@Component
class PaymentCreator(
    private val transactionRepository: TransactionRepository,
    private val outboxRepository: OutboxEventRepository,
    private val transactionEventRepository: TransactionEventRepository
) {

    @Transactional
    fun create(
        merchantId: UUID,
        idempotencyKey: String,
        requestHash: String,
        request: CreatePaymentRequest
    ): Transaction {
        // Stays CREATED; the dispatcher moves it to PENDING when it contacts the provider.
        val transaction = transactionRepository.save(
            Transaction(
                merchantId = merchantId,
                idempotencyKey = idempotencyKey,
                requestHash = requestHash,
                amount = request.amount,
                currency = request.currency.uppercase(),
                description = request.description,
                paymentMethod = request.paymentMethod
            )
        )

        // Opening history event: no prior state.
        transactionEventRepository.save(
            TransactionEvent(
                transactionId = transaction.id,
                fromStatus = null,
                toStatus = transaction.status
            )
        )

        outboxRepository.save(
            OutboxEvent(
                aggregateId = transaction.id,
                type = OutboxEvent.PROVIDER_AUTHORIZATION
            )
        )

        return transaction
    }
}
