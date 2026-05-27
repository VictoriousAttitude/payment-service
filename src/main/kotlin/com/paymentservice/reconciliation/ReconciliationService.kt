package com.paymentservice.reconciliation

import com.paymentservice.ledger.LedgerRepository
import com.paymentservice.payment.PaymentStatus
import com.paymentservice.payment.Transaction
import com.paymentservice.payment.TransactionRepository
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class ReconciliationService(
    private val transactionRepository: TransactionRepository,
    private val ledgerRepository: LedgerRepository
) {

    companion object {
        private val NON_TERMINAL_STATUSES = setOf(
            PaymentStatus.CREATED,
            PaymentStatus.PENDING,
            PaymentStatus.AUTHORIZED
        )
    }

    /**
     * Q1: Did the process complete correctly?
     * Finds transactions stuck in non-terminal states beyond a threshold.
     * A transaction in PENDING for 30+ minutes likely means the provider callback was lost.
     */
    fun findStuckTransactions(threshold: Duration = Duration.ofMinutes(30)): List<Transaction> {
        val cutoff = Instant.now().minus(threshold)
        return transactionRepository.findStuckTransactions(NON_TERMINAL_STATUSES, cutoff)
    }

    /**
     * Q3a: Is all the expected data present?
     * Finds CAPTURED/SETTLED/REFUNDED transactions that have no ledger entries.
     * If this returns anything, data is missing — a critical consistency failure.
     */
    fun findTransactionsWithoutLedgerEntries(): List<Transaction> {
        return transactionRepository.findWithoutLedgerEntries()
    }

    /**
     * Q3b: Is all the expected data correct?
     * Finds transactions where ledger entries don't balance (debits != credits).
     * Should always return empty — any result indicates a ledger corruption.
     */
    fun findUnbalancedTransactions(): List<UUID> {
        return ledgerRepository.findUnbalancedTransactions()
    }

    /**
     * Global system health: do all debits equal all credits across the entire ledger?
     * If false, money has appeared from nowhere or disappeared. Red alert.
     */
    fun verifyGlobalLedgerBalance(): GlobalBalanceResult {
        val totalDebits = ledgerRepository.sumAllDebits()
        val totalCredits = ledgerRepository.sumAllCredits()
        return GlobalBalanceResult(
            totalDebits = totalDebits,
            totalCredits = totalCredits,
            balanced = totalDebits == totalCredits
        )
    }

    /**
     * Full reconciliation report: runs all checks and returns a summary.
     */
    fun runFullReconciliation(stuckThreshold: Duration = Duration.ofMinutes(30)): ReconciliationReport {
        val stuckTransactions = findStuckTransactions(stuckThreshold)
        val missingEntries = findTransactionsWithoutLedgerEntries()
        val unbalanced = findUnbalancedTransactions()
        val globalBalance = verifyGlobalLedgerBalance()

        return ReconciliationReport(
            stuckTransactions = stuckTransactions.map { it.id },
            transactionsWithoutLedgerEntries = missingEntries.map { it.id },
            unbalancedTransactions = unbalanced,
            globalBalance = globalBalance,
            healthy = stuckTransactions.isEmpty()
                    && missingEntries.isEmpty()
                    && unbalanced.isEmpty()
                    && globalBalance.balanced
        )
    }
}

data class GlobalBalanceResult(
    val totalDebits: Long,
    val totalCredits: Long,
    val balanced: Boolean
)

data class ReconciliationReport(
    val stuckTransactions: List<UUID>,
    val transactionsWithoutLedgerEntries: List<UUID>,
    val unbalancedTransactions: List<UUID>,
    val globalBalance: GlobalBalanceResult,
    val healthy: Boolean
)
