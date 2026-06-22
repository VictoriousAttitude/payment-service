package com.paymentservice.reconciliation

import com.paymentservice.ledger.CurrencyBalance
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
            PaymentStatus.PARTIALLY_CAPTURED,
            PaymentStatus.CAPTURED,
            PaymentStatus.SETTLED,
            PaymentStatus.PARTIALLY_REFUNDED,
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
     * Q3c: Is the data present exactly once, within bounds?
     * Finds transactions whose ledger totals violate the partial-capture/refund
     * invariants: captured <= authorized amount, refunded <= captured. A
     * duplicated capture set (or runaway refund) is balanced per-transaction and
     * globally — debit==credit checks are blind to it. Comparing the derived
     * captured/refunded totals against the source-of-truth amount is the only
     * check that catches it.
     */
    fun findTransactionsWithMismatchedAmounts(): List<UUID> {
        return transactionRepository.findAmountInvariantViolations(STATUSES_REQUIRING_LEDGER)
    }

    /**
     * Global system health: within every currency, do all debits equal all
     * credits? Checked per-currency because the invariant is per-currency — a
     * cross-currency SUM could net a EUR shortfall against a USD surplus and
     * falsely report "balanced". If any currency is off, money has appeared
     * from nowhere or disappeared. Red alert.
     */
    fun verifyGlobalLedgerBalance(): GlobalBalanceResult {
        return GlobalBalanceResult(byCurrency = ledgerRepository.sumByCurrency())
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
    val byCurrency: List<CurrencyBalance>
) {
    val balanced: Boolean get() = byCurrency.all { it.balanced }
}

data class ReconciliationReport(
    val stuckTransactions: List<UUID>,
    val transactionsWithoutLedgerEntries: List<UUID>,
    val unbalancedTransactions: List<UUID>,
    val amountMismatchedTransactions: List<UUID>,
    val globalBalance: GlobalBalanceResult,
    val healthy: Boolean
)
