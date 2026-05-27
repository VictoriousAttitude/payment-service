package com.paymentservice.ledger

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface LedgerRepository : JpaRepository<LedgerEntry, UUID> {

    fun findByTransactionId(transactionId: UUID): List<LedgerEntry>

    @Query("""
        SELECT COALESCE(SUM(CASE WHEN le.entryType = 'CREDIT' THEN le.amount ELSE 0 END), 0) -
               COALESCE(SUM(CASE WHEN le.entryType = 'DEBIT' THEN le.amount ELSE 0 END), 0)
        FROM LedgerEntry le
        WHERE le.accountType = :accountType AND le.accountId = :accountId
    """)
    fun computeBalance(accountType: AccountType, accountId: UUID): Long

    @Query("""
        SELECT COALESCE(SUM(CASE WHEN le.entryType = 'DEBIT' THEN le.amount ELSE 0 END), 0)
        FROM LedgerEntry le
    """)
    fun sumAllDebits(): Long

    @Query("""
        SELECT COALESCE(SUM(CASE WHEN le.entryType = 'CREDIT' THEN le.amount ELSE 0 END), 0)
        FROM LedgerEntry le
    """)
    fun sumAllCredits(): Long

    @Query("""
        SELECT le.transactionId FROM LedgerEntry le
        GROUP BY le.transactionId
        HAVING SUM(CASE WHEN le.entryType = 'DEBIT' THEN le.amount ELSE 0 END) <>
               SUM(CASE WHEN le.entryType = 'CREDIT' THEN le.amount ELSE 0 END)
    """)
    fun findUnbalancedTransactions(): List<UUID>
}
