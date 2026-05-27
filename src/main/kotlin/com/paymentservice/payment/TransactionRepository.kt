package com.paymentservice.payment

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface TransactionRepository : JpaRepository<Transaction, UUID> {

    fun findByMerchantIdAndIdempotencyKey(merchantId: UUID, idempotencyKey: String): Transaction?

    fun findByMerchantId(merchantId: UUID, pageable: Pageable): Page<Transaction>

    fun findByMerchantIdAndStatus(merchantId: UUID, status: PaymentStatus, pageable: Pageable): Page<Transaction>

    @Query("""
        SELECT t FROM Transaction t
        WHERE t.status IN :statuses
        AND t.createdAt < :before
    """)
    fun findStuckTransactions(statuses: Collection<PaymentStatus>, before: Instant): List<Transaction>

    @Query("""
        SELECT t FROM Transaction t
        WHERE t.status IN ('CAPTURED', 'SETTLED', 'REFUNDED')
        AND t.id NOT IN (SELECT DISTINCT le.transactionId FROM LedgerEntry le)
    """)
    fun findWithoutLedgerEntries(): List<Transaction>
}
