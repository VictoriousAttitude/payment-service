package com.paymentservice.payment

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PaymentOperationRepository : JpaRepository<PaymentOperation, UUID> {

    fun findByTransactionIdAndIdempotencyKey(transactionId: UUID, idempotencyKey: String): PaymentOperation?

    fun findByTransactionIdOrderByCreatedAtAsc(transactionId: UUID): List<PaymentOperation>
}
