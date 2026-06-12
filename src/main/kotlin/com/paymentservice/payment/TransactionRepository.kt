package com.paymentservice.payment

import com.paymentservice.ledger.EntryType
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
        WHERE t.status IN :statuses
        AND t.id NOT IN (SELECT DISTINCT le.transactionId FROM LedgerEntry le)
    """)
    fun findWithoutLedgerEntries(statuses: Collection<PaymentStatus>): List<Transaction>

    /**
     * Finds transactions whose total ledger debits do not match the expected
     * multiple of the transaction amount (1x for captured, 2x for refunded —
     * capture set + refund set each debit the full amount).
     * Catches duplicated entry sets that are individually balanced and thus
     * invisible to debit==credit checks.
     */
    @Query("""
        SELECT t.id FROM Transaction t
        WHERE t.status IN :statuses
        AND (t.amount * :multiplier) <>
            (SELECT COALESCE(SUM(le.amount), 0)
             FROM LedgerEntry le
             WHERE le.transactionId = t.id AND le.entryType = :entryType)
    """)
    fun findWithMismatchedDebitTotal(
        statuses: Collection<PaymentStatus>,
        multiplier: Long,
        entryType: EntryType
    ): List<UUID>
}
