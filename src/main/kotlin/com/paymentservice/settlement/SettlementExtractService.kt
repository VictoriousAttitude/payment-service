package com.paymentservice.settlement

import com.paymentservice.ledger.LedgerRepository
import com.paymentservice.payment.TransactionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.temporal.ChronoUnit

/**
 * Exports the live double-entry ledger as the movement-level CSV the external
 * `recon` engine consumes, so the oracle reconciles the real database instead
 * of a fixture. The same extract feeds [SettlementFileReconciler], the online
 * operational control that matches uploaded acquirer files; the Python engine
 * remains the independent offline oracle, and the three-way check requires
 * both verdicts on the same file to agree.
 *
 * Lives in the settlement module, which already depends on payment and may
 * depend on ledger: a source module reading both, never a cycle.
 */
@Service
class SettlementExtractService(
    private val transactionRepository: TransactionRepository,
    private val ledgerRepository: LedgerRepository
) {

    /**
     * Projects every transaction's ledger entries into movement lines. Two
     * queries, not N+1: all transactions for the reference map, all entries
     * grouped in memory. The base reference is the provider reference when the
     * acquirer assigned one, falling back to the transaction id so a movement is
     * never referenceless (recon flags an empty reference as unmatched).
     */
    @Transactional(readOnly = true)
    fun extract(): List<MovementLine> {
        val baseByTransaction = transactionRepository.findAll()
            .associate { it.id to (it.providerReference ?: it.id.toString()) }
        return ledgerRepository.findAll()
            // Treasury postings (payouts, reserve releases) have no transaction:
            // they are internal money movements, not acquirer movements, so the
            // recon oracle - which reconciles against the acquirer's clearing
            // file - must never see them.
            .filter { it.transactionId != null }
            .groupBy { it.transactionId!! }
            .flatMap { (transactionId, entries) ->
                val base = baseByTransaction[transactionId] ?: transactionId.toString()
                SettlementExtractor.extract(base, entries)
            }
            .sortedWith(compareBy({ it.reference }, { it.kind }))
    }

    /**
     * Renders the lines as the `csv_ledger` contract recon parses:
     * `reference,kind,gross_minor,fee_minor,currency,occurred_at`. The timestamp
     * is truncated to whole seconds so it is plain ISO 8601 with a `Z` offset,
     * which the engine's `datetime.fromisoformat` accepts on every Python it
     * targets (sub-second precision is not portable across older runtimes).
     */
    fun toCsv(lines: List<MovementLine>): String {
        val header = "reference,kind,gross_minor,fee_minor,currency,occurred_at"
        val rows = lines.joinToString("\n") { line ->
            listOf(
                line.reference,
                line.kind.name,
                line.grossMinor.toString(),
                line.feeMinor.toString(),
                line.currency,
                line.occurredAt.truncatedTo(ChronoUnit.SECONDS).toString()
            ).joinToString(",")
        }
        return if (rows.isEmpty()) "$header\n" else "$header\n$rows\n"
    }
}
