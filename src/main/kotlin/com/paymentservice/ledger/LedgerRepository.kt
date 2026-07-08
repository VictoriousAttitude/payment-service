package com.paymentservice.ledger

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface LedgerRepository : JpaRepository<LedgerEntry, UUID> {

    fun findByTransactionId(transactionId: UUID): List<LedgerEntry>

    fun findByPostingGroupId(postingGroupId: UUID): List<LedgerEntry>

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

    /**
     * Groups by postingGroupId, not transactionId: transactionId is nullable
     * (treasury postings), so grouping by it would lump every payout/reserve
     * posting into one NULL bucket and surface `null` in a List<UUID>.
     */
    @Query("""
        SELECT le.postingGroupId FROM LedgerEntry le
        GROUP BY le.postingGroupId
        HAVING SUM(CASE WHEN le.entryType = 'DEBIT' THEN le.amount ELSE 0 END) <>
               SUM(CASE WHEN le.entryType = 'CREDIT' THEN le.amount ELSE 0 END)
    """)
    fun findUnbalancedPostingGroups(): List<UUID>

    /**
     * Merchants whose net payable balance reaches [minimum], one row per
     * (merchant, currency): the auto-payout batch's work queue.
     */
    @Query("""
        SELECT new com.paymentservice.ledger.AccountCurrencyBalance(
            le.accountId,
            le.currency,
            SUM(CASE WHEN le.entryType = 'DEBIT' THEN le.amount ELSE 0 END),
            SUM(CASE WHEN le.entryType = 'CREDIT' THEN le.amount ELSE 0 END))
        FROM LedgerEntry le
        WHERE le.accountType = com.paymentservice.ledger.AccountType.MERCHANT_PAYABLE
        GROUP BY le.accountId, le.currency
        HAVING SUM(CASE WHEN le.entryType = 'CREDIT' THEN le.amount ELSE 0 END) -
               SUM(CASE WHEN le.entryType = 'DEBIT' THEN le.amount ELSE 0 END) >= :minimum
    """)
    fun payableBalancesAtLeast(minimum: Long): List<AccountCurrencyBalance>

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
     * The merchant's net position on the MERCHANT (pending) account for one
     * transaction: capture credits minus refund/chargeback debits. What the
     * settlement split moves out of pending.
     */
    @Query("""
        SELECT COALESCE(SUM(CASE WHEN le.entryType = 'CREDIT' THEN le.amount ELSE -le.amount END), 0)
        FROM LedgerEntry le
        WHERE le.transactionId = :transactionId
        AND le.accountType = com.paymentservice.ledger.AccountType.MERCHANT
    """)
    fun merchantNetForTransaction(transactionId: UUID): Long

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

    /**
     * Net balance (credits - debits) for one account/currency over only the
     * entries created strictly after [cutoff]: the live delta added on top of
     * the folded snapshot. Mirrors [computeBalance] but bounded to the tail the
     * snapshot has not yet absorbed, so a balance read costs O(entries since
     * the last fold) instead of O(all history).
     */
    @Query("""
        SELECT COALESCE(SUM(CASE WHEN le.entryType = 'CREDIT' THEN le.amount ELSE 0 END), 0) -
               COALESCE(SUM(CASE WHEN le.entryType = 'DEBIT' THEN le.amount ELSE 0 END), 0)
        FROM LedgerEntry le
        WHERE le.accountType = :accountType AND le.accountId = :accountId
          AND le.currency = :currency AND le.createdAt > :cutoff
    """)
    fun netSince(accountType: AccountType, accountId: UUID, currency: String, cutoff: Instant): Long

    /**
     * Per-account debit/credit deltas for entries created in (previous, cutoff],
     * one row per (account_type, account_id, currency): the fold window the
     * snapshotter adds to the checkpoint each run.
     */
    @Query("""
        SELECT new com.paymentservice.ledger.SnapshotDelta(
            le.accountType, le.accountId, le.currency,
            SUM(CASE WHEN le.entryType = 'DEBIT' THEN le.amount ELSE 0 END),
            SUM(CASE WHEN le.entryType = 'CREDIT' THEN le.amount ELSE 0 END))
        FROM LedgerEntry le
        WHERE le.createdAt > :previous AND le.createdAt <= :cutoff
        GROUP BY le.accountType, le.accountId, le.currency
    """)
    fun aggregateBetween(previous: Instant, cutoff: Instant): List<SnapshotDelta>

    /**
     * Per-account debit/credit totals over all entries at or before [cutoff]:
     * the exact checkpoint the snapshot table should hold at cursor = [cutoff].
     * Reconciliation re-derives this and compares it to the materialized rows
     * to detect snapshot drift.
     */
    @Query("""
        SELECT new com.paymentservice.ledger.SnapshotDelta(
            le.accountType, le.accountId, le.currency,
            SUM(CASE WHEN le.entryType = 'DEBIT' THEN le.amount ELSE 0 END),
            SUM(CASE WHEN le.entryType = 'CREDIT' THEN le.amount ELSE 0 END))
        FROM LedgerEntry le
        WHERE le.createdAt <= :cutoff
        GROUP BY le.accountType, le.accountId, le.currency
    """)
    fun aggregateUpTo(cutoff: Instant): List<SnapshotDelta>
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

/** [CurrencyBalance] keyed by the owning account: one (account, currency) row. */
data class AccountCurrencyBalance(
    val accountId: UUID,
    val currency: String,
    val totalDebits: Long,
    val totalCredits: Long
) {
    val net: Long get() = totalCredits - totalDebits
}

/**
 * Debit/credit totals for one (account_type, account_id, currency) over a
 * created_at window: the unit the snapshotter folds and the reconciler
 * re-derives.
 */
data class SnapshotDelta(
    val accountType: AccountType,
    val accountId: UUID,
    val currency: String,
    val totalDebits: Long,
    val totalCredits: Long
)
