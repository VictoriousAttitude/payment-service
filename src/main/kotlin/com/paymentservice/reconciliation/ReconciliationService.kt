package com.paymentservice.reconciliation

import com.paymentservice.ledger.BalanceSnapshotRepository
import com.paymentservice.ledger.CurrencyBalance
import com.paymentservice.ledger.LedgerRepository
import com.paymentservice.ledger.SnapshotCursorRepository
import com.paymentservice.ledger.SnapshotProcessor
import com.paymentservice.payment.PaymentStatus
import com.paymentservice.payment.Transaction
import com.paymentservice.payment.TransactionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class ReconciliationService(
    private val transactionRepository: TransactionRepository,
    private val ledgerRepository: LedgerRepository,
    private val snapshotRepository: BalanceSnapshotRepository,
    private val cursorRepository: SnapshotCursorRepository
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
     * Finds posting groups whose entries don't balance (debits != credits).
     * Should always return empty — any result indicates a ledger corruption.
     */
    fun findUnbalancedPostingGroups(): List<UUID> {
        return ledgerRepository.findUnbalancedPostingGroups()
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
     * Q4: Does the balance acceleration still agree with the ledger? Re-derives
     * the exact checkpoint (debit/credit totals over every entry at or before
     * the snapshot cursor) and compares it to the materialized snapshot rows.
     * The snapshot is a derived cache with no append-only trigger, so this is
     * the only guard that a fold bug or a manual write left it out of step with
     * the immutable ledger. Any result means balance reads could be wrong; the
     * snapshot is safe to rebuild from scratch. Returns one line per drifting
     * (account, currency).
     */
    fun findSnapshotDrift(): List<String> {
        val cursor = cursorRepository.findById(SnapshotProcessor.CURSOR_ID).orElseThrow().asOf
        val expected = ledgerRepository.aggregateUpTo(cursor)
            .associate { Triple(it.accountType, it.accountId, it.currency) to (it.totalDebits to it.totalCredits) }
        val actual = snapshotRepository.findAll()
            .associate { Triple(it.accountType, it.accountId, it.currency) to (it.totalDebits to it.totalCredits) }
        return (expected.keys + actual.keys).mapNotNull { key ->
            val (ed, ec) = expected[key] ?: (0L to 0L)
            val (ad, ac) = actual[key] ?: (0L to 0L)
            if (ed != ad || ec != ac) {
                "${key.first}/${key.second}/${key.third} snapshot=($ad,$ac) ledger=($ed,$ec)"
            } else {
                null
            }
        }
    }

    /**
     * Full reconciliation report: runs all checks and returns a summary. Read
     * in one read-only transaction so every check - including the snapshot
     * drift derivation - sees a single consistent MVCC view.
     */
    @Transactional(readOnly = true)
    fun runFullReconciliation(stuckThreshold: Duration = Duration.ofMinutes(30)): ReconciliationReport {
        val stuckTransactions = findStuckTransactions(stuckThreshold)
        val missingEntries = findTransactionsWithoutLedgerEntries()
        val unbalanced = findUnbalancedPostingGroups()
        val mismatchedAmounts = findTransactionsWithMismatchedAmounts()
        val globalBalance = verifyGlobalLedgerBalance()
        val snapshotDrift = findSnapshotDrift()

        return ReconciliationReport(
            stuckTransactions = stuckTransactions.map { it.id },
            transactionsWithoutLedgerEntries = missingEntries.map { it.id },
            unbalancedPostingGroups = unbalanced,
            amountMismatchedTransactions = mismatchedAmounts,
            globalBalance = globalBalance,
            snapshotDrift = snapshotDrift,
            healthy = stuckTransactions.isEmpty()
                    && missingEntries.isEmpty()
                    && unbalanced.isEmpty()
                    && mismatchedAmounts.isEmpty()
                    && globalBalance.balanced
                    && snapshotDrift.isEmpty()
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
    val unbalancedPostingGroups: List<UUID>,
    val amountMismatchedTransactions: List<UUID>,
    val globalBalance: GlobalBalanceResult,
    val snapshotDrift: List<String>,
    val healthy: Boolean
)
