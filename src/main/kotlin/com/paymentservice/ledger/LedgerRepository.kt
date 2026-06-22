package com.paymentservice.ledger

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface LedgerRepository : JpaRepository<LedgerEntry, UUID> {

    fun findByTransactionId(transactionId: UUID): List<LedgerEntry>

    /**
     * Net balance (credits - debits) for an account in a single currency.
     * Currency is part of the key: summing across currencies would add cents of
     * different denominations into a meaningless number.
     */
    @Query("""
        SELECT COALESCE(SUM(CASE WHEN le.entryType = 'CREDIT' THEN le.amount ELSE 0 END), 0) -
               COALESCE(SUM(CASE WHEN le.entryType = 'DEBIT' THEN le.amount ELSE 0 END), 0)
        FROM LedgerEntry le
        WHERE le.accountType = :accountType AND le.accountId = :accountId AND le.currency = :currency
    """)
    fun computeBalance(accountType: AccountType, accountId: UUID, currency: String): Long

    /**
     * Per-currency debit/credit totals for one account, one row per currency.
     */
    @Query("""
        SELECT new com.paymentservice.ledger.CurrencyBalance(
            le.currency,
            SUM(CASE WHEN le.entryType = 'DEBIT' THEN le.amount ELSE 0 END),
            SUM(CASE WHEN le.entryType = 'CREDIT' THEN le.amount ELSE 0 END))
        FROM LedgerEntry le
        WHERE le.accountType = :accountType AND le.accountId = :accountId
        GROUP BY le.currency
    """)
    fun computeBalancesByCurrency(accountType: AccountType, accountId: UUID): List<CurrencyBalance>

    /**
     * Per-currency debit/credit totals across the whole ledger. The global
     * balance invariant (debits == credits) only holds within a currency;
     * a single cross-currency SUM could net a EUR shortfall against a USD
     * surplus and falsely report "balanced".
     */
    @Query("""
        SELECT new com.paymentservice.ledger.CurrencyBalance(
            le.currency,
            SUM(CASE WHEN le.entryType = 'DEBIT' THEN le.amount ELSE 0 END),
            SUM(CASE WHEN le.entryType = 'CREDIT' THEN le.amount ELSE 0 END))
        FROM LedgerEntry le
        GROUP BY le.currency
    """)
    fun sumByCurrency(): List<CurrencyBalance>

    @Query("""
        SELECT le.transactionId FROM LedgerEntry le
        GROUP BY le.transactionId
        HAVING SUM(CASE WHEN le.entryType = 'DEBIT' THEN le.amount ELSE 0 END) <>
               SUM(CASE WHEN le.entryType = 'CREDIT' THEN le.amount ELSE 0 END)
    """)
    fun findUnbalancedTransactions(): List<UUID>

    /**
     * Total captured: the INCOMING DEBIT leg is posted once per capture for the
     * captured amount, so its running sum is the amount captured to date. This
     * derives the remaining-capturable headroom for partial/multi-capture.
     */
    @Query("""
        SELECT COALESCE(SUM(le.amount), 0)
        FROM LedgerEntry le
        WHERE le.transactionId = :transactionId
        AND le.accountType = com.paymentservice.ledger.AccountType.INCOMING
        AND le.entryType = com.paymentservice.ledger.EntryType.DEBIT
    """)
    fun sumCaptured(transactionId: UUID): Long

    /**
     * Total refunded: the OUTGOING CREDIT leg is posted once per refund for the
     * refunded amount, so its running sum is the amount refunded to date.
     */
    @Query("""
        SELECT COALESCE(SUM(le.amount), 0)
        FROM LedgerEntry le
        WHERE le.transactionId = :transactionId
        AND le.accountType = com.paymentservice.ledger.AccountType.OUTGOING
        AND le.entryType = com.paymentservice.ledger.EntryType.CREDIT
    """)
    fun sumRefunded(transactionId: UUID): Long
}

/**
 * Debit/credit totals for a single currency. `net` is the account-balance view
 * (credits - debits); `balanced` is the ledger-invariant view (debits == credits).
 */
data class CurrencyBalance(
    val currency: String,
    val totalDebits: Long,
    val totalCredits: Long
) {
    val net: Long get() = totalCredits - totalDebits
    val balanced: Boolean get() = totalDebits == totalCredits
}
