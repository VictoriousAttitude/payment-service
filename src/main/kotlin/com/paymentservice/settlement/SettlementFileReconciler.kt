package com.paymentservice.settlement

import java.time.Duration
import java.time.Instant

/**
 * Discrepancy classes, name-identical to the Python oracle's `DiscrepancyType`
 * so the two verdicts can be compared literally in the three-way check.
 */
enum class FileDiscrepancyType {
    MISSING_IN_LEDGER,
    MISSING_IN_SETTLEMENT,
    KIND_MISMATCH,
    CURRENCY_MISMATCH,
    GROSS_MISMATCH,
    FEE_MISMATCH,
    DUPLICATE_REFERENCE
}

data class FileDiscrepancy(
    val type: FileDiscrepancyType,
    val reference: String,
    val detail: String,
    val ledgerValue: String? = null,
    val settlementValue: String? = null
)

/**
 * @property matchedCount references present and comparable on both sides
 * @property pending ledger-only references still inside the settlement window:
 *   not yet expected in the file, so expected state rather than a discrepancy
 */
data class FileReconciliationResult(
    val matchedCount: Int,
    val pending: List<String>,
    val discrepancies: List<FileDiscrepancy>
)

/**
 * Pure matcher between the live-ledger movement extract and a parsed acquirer
 * settlement file. Semantics mirror the Python oracle's `reconcile()` exactly,
 * by design: the JVM matcher is the online operational control, the Python
 * engine the independent offline oracle, and the three-way check requires
 * their verdicts to agree.
 *
 * Shared semantics (see recon/src/recon/domain/reconcile.py):
 *  - sides join on `reference`; every reference lands in exactly one bucket
 *    (matched, ledger-only, settlement-only), so the partition is exhaustive;
 *  - a duplicated reference is ambiguous to match: it is excluded from the
 *    index and flagged as its own discrepancy instead of silently deduped;
 *  - ledger-only movements newer than `asOf - window` are pending, not missing;
 *  - a currency mismatch short-circuits the amount checks, because
 *    cross-currency minor units are not comparable.
 *
 * Reference grain is per (transaction, kind) with `:refund`/`:chargeback`
 * suffixes, not ARN-level: two transactions sharing a providerReference
 * correctly surface as a ledger-side DUPLICATE_REFERENCE.
 */
object SettlementFileReconciler {

    fun reconcile(
        ledger: List<MovementLine>,
        file: List<AcquirerSettlementLine>,
        asOf: Instant,
        window: Duration
    ): FileReconciliationResult {
        val discrepancies = mutableListOf<FileDiscrepancy>()

        val (ledgerIndex, ledgerDupes) = index(ledger) { it.reference }
        val (fileIndex, fileDupes) = index(file) { it.reference }
        discrepancies += duplicates(ledgerDupes, "ledger extract")
        discrepancies += duplicates(fileDupes, "settlement file")

        val matched = ledgerIndex.keys.intersect(fileIndex.keys).sorted()
        matched.forEach { ref ->
            discrepancies += compare(ledgerIndex.getValue(ref), fileIndex.getValue(ref))
        }

        val pending = mutableListOf<String>()
        val cutoff = asOf.minus(window)
        (ledgerIndex.keys - fileIndex.keys).sorted().forEach { ref ->
            val line = ledgerIndex.getValue(ref)
            if (line.occurredAt.isAfter(cutoff)) {
                pending += ref
            } else {
                discrepancies += FileDiscrepancy(
                    FileDiscrepancyType.MISSING_IN_SETTLEMENT,
                    ref,
                    "booked in the ledger but absent from the settlement file",
                    ledgerValue = line.grossMinor.toString()
                )
            }
        }

        (fileIndex.keys - ledgerIndex.keys).sorted().forEach { ref ->
            discrepancies += FileDiscrepancy(
                FileDiscrepancyType.MISSING_IN_LEDGER,
                ref,
                "settled by the acquirer but never booked in the ledger",
                settlementValue = fileIndex.getValue(ref).grossMinor.toString()
            )
        }

        return FileReconciliationResult(matched.size, pending, discrepancies)
    }

    /**
     * Index rows by reference; a colliding key is removed from the index and
     * reported, because silent dedup would let a double-booking pass unnoticed.
     */
    private fun <T> index(rows: List<T>, key: (T) -> String): Pair<Map<String, T>, Set<String>> {
        val index = mutableMapOf<String, T>()
        val duplicated = mutableSetOf<String>()
        for (row in rows) {
            val k = key(row)
            if (k in index || k in duplicated) {
                duplicated += k
                index.remove(k)
            } else {
                index[k] = row
            }
        }
        return index to duplicated
    }

    private fun duplicates(refs: Set<String>, side: String): List<FileDiscrepancy> =
        refs.sorted().map { ref ->
            FileDiscrepancy(
                FileDiscrepancyType.DUPLICATE_REFERENCE,
                ref,
                "reference appears more than once in the $side"
            )
        }

    private fun compare(ledger: MovementLine, file: AcquirerSettlementLine): List<FileDiscrepancy> {
        val found = mutableListOf<FileDiscrepancy>()
        if (ledger.kind != file.kind) {
            found += FileDiscrepancy(
                FileDiscrepancyType.KIND_MISMATCH,
                ledger.reference,
                "movement kind differs",
                ledgerValue = ledger.kind.name,
                settlementValue = file.kind.name
            )
        }
        if (ledger.currency != file.currency) {
            // cross-currency: minor units are not comparable, stop after flagging
            found += FileDiscrepancy(
                FileDiscrepancyType.CURRENCY_MISMATCH,
                ledger.reference,
                "currency differs",
                ledgerValue = ledger.currency,
                settlementValue = file.currency
            )
            return found
        }
        if (ledger.grossMinor != file.grossMinor) {
            found += FileDiscrepancy(
                FileDiscrepancyType.GROSS_MISMATCH,
                ledger.reference,
                "gross amount differs",
                ledgerValue = ledger.grossMinor.toString(),
                settlementValue = file.grossMinor.toString()
            )
        }
        if (ledger.feeMinor != file.feeMinor) {
            found += FileDiscrepancy(
                FileDiscrepancyType.FEE_MISMATCH,
                ledger.reference,
                "fee differs (possible margin leak)",
                ledgerValue = ledger.feeMinor.toString(),
                settlementValue = file.feeMinor.toString()
            )
        }
        return found
    }
}
