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

    /**
     * Captured transactions due for settlement: oldest first, past the settlement
     * delay. updatedAt is the capture time for a CAPTURED row — capture was its
     * last write — so it doubles as the settlement clock.
     */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.status = com.paymentservice.payment.PaymentStatus.CAPTURED
        AND t.updatedAt <= :cutoff
        ORDER BY t.updatedAt ASC
    """)
    fun findSettlable(cutoff: Instant, pageable: Pageable): List<Transaction>

    // NOT EXISTS, not NOT IN: a NULL transactionId in the subquery would make
    // NOT IN evaluate to UNKNOWN and silently return zero rows, masking the very
    // data-integrity gaps this query exists to find.
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.status IN :statuses
        AND NOT EXISTS (SELECT 1 FROM LedgerEntry le WHERE le.transactionId = t.id)
    """)
    fun findWithoutLedgerEntries(statuses: Collection<PaymentStatus>): List<Transaction>

    /**
     * Finds transactions that violate the partial-capture/refund money
     * invariants, derived from the ledger:
     *   captured (sum of INCOMING DEBIT)  <= authorized amount, and
     *   refunded (sum of OUTGOING CREDIT) <= captured.
     * A duplicated capture set pushes captured past the amount; a runaway refund
     * pushes refunded past captured. Both are balanced per-transaction (debit ==
     * credit) and so invisible to the imbalance check — this is the only check
     * that compares entry totals against the source-of-truth amount and against
     * each other.
     */
    @Query("""
        SELECT t.id FROM Transaction t
        WHERE t.status IN :statuses
        AND (
            (SELECT COALESCE(SUM(le.amount), 0) FROM LedgerEntry le
             WHERE le.transactionId = t.id
             AND le.accountType = com.paymentservice.ledger.AccountType.INCOMING
             AND le.entryType = com.paymentservice.ledger.EntryType.DEBIT) > t.amount
            OR
            (SELECT COALESCE(SUM(le.amount), 0) FROM LedgerEntry le
             WHERE le.transactionId = t.id
             AND le.accountType = com.paymentservice.ledger.AccountType.OUTGOING
             AND le.entryType = com.paymentservice.ledger.EntryType.CREDIT)
            >
            (SELECT COALESCE(SUM(le2.amount), 0) FROM LedgerEntry le2
             WHERE le2.transactionId = t.id
             AND le2.accountType = com.paymentservice.ledger.AccountType.INCOMING
             AND le2.entryType = com.paymentservice.ledger.EntryType.DEBIT)
        )
    """)
    fun findAmountInvariantViolations(statuses: Collection<PaymentStatus>): List<UUID>
}
