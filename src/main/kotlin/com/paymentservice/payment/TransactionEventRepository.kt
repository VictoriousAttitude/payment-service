package com.paymentservice.payment

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TransactionEventRepository : JpaRepository<TransactionEvent, UUID> {

    fun findByTransactionIdOrderByCreatedAtAsc(transactionId: UUID): List<TransactionEvent>
}
