package com.paymentservice.reconciliation

import com.paymentservice.ledger.EntryType
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
        private val STATUSES_REQUIRING_LEDGER = setOf(
            PaymentStatus.CAPTURED,
            PaymentStatus.SETTLED,
            PaymentStatus.REFUNDED
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
        return transactionRepository.findWithoutLedgerEntries(STATUSES_REQUIRING_LEDGER)
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
     * Q3c: Is the data present exactly once?
     * Finds transactions whose ledger debit total does not match the expected
     * amount. A duplicated capture entry set is balanced per-transaction and
     * globally — debit==credit checks are blind to it. Comparing entry totals
     * against the source-of-truth transaction amount is the only check that
     * catches duplication.
     */
    fun findTransactionsWithMismatchedAmounts(): List<UUID> {
        // CAPTURED/SETTLED: one capture set debits exactly `amount`
        val captured = transactionRepository.findWithMismatchedDebitTotal(
            setOf(PaymentStatus.CAPTURED, PaymentStatus.SETTLED), 1L, EntryType.DEBIT
        )
        // REFUNDED: capture set + refund set each debit `amount` -> 2x
        val refunded = transactionRepository.findWithMismatchedDebitTotal(
            setOf(PaymentStatus.REFUNDED), 2L, EntryType.DEBIT
        )
        return captured + refunded
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
        val mismatchedAmounts = findTransactionsWithMismatchedAmounts()
        val globalBalance = verifyGlobalLedgerBalance()

        return ReconciliationReport(
            stuckTransactions = stuckTransactions.map { it.id },
            transactionsWithoutLedgerEntries = missingEntries.map { it.id },
            unbalancedTransactions = unbalanced,
            amountMismatchedTransactions = mismatchedAmounts,
            globalBalance = globalBalance,
            healthy = stuckTransactions.isEmpty()
                    && missingEntries.isEmpty()
                    && unbalanced.isEmpty()
                    && mismatchedAmounts.isEmpty()
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
    val amountMismatchedTransactions: List<UUID>,
    val globalBalance: GlobalBalanceResult,
    val healthy: Boolean
)
