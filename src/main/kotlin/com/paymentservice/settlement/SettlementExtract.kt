package com.paymentservice.settlement

import com.paymentservice.ledger.AccountType
import com.paymentservice.ledger.EntryType
import com.paymentservice.ledger.LedgerEntry
import com.paymentservice.ledger.LedgerService
import java.time.Instant

/**
 * A single settlement movement, the unit the reconciliation oracle matches on.
 * Mirrors the `recon` engine's `LedgerLine` (reference, kind, gross, fee,
 * currency, occurred_at), which is the contract this extract feeds.
 */
enum class MovementKind { CAPTURE, REFUND, CHARGEBACK }

/**
 * One movement line in the clearing extract. Amounts are integer minor units,
 * signed by direction: an inflow (capture) is positive, an outflow (refund,
 * chargeback) is negative. `feeMinor` is the platform's fee component of the
 * movement, signed the same way the platform was posted: collected on a capture
 * or chargeback (positive), returned on a refund (negative).
 */
data class MovementLine(
    val reference: String,
    val kind: MovementKind,
    val grossMinor: Long,
    val feeMinor: Long,
    val currency: String,
    val occurredAt: Instant
)

/**
 * Pure projection from a transaction's double-entry ledger to settlement
 * movement lines, one line per (transaction, kind). No I/O, so it is unit and
 * mutation testable in isolation.
 *
 * Aggregating per kind (not per ledger posting) is deliberate: partial and
 * multi-capture post several capture sets to one transaction, but the entries
 * carry no posting-group id, so a single posting cannot be reconstructed from
 * the rows. A processor settlement file reports one net figure per category per
 * transaction anyway, so the per-kind total is the right grain to reconcile on
 * and the reference stays unique per movement.
 *
 * Leg selection:
 *  - gross is read from the cardholder-facing leg, unambiguous by account type
 *    (capture = INCOMING DEBIT, refund = OUTGOING CREDIT, chargeback =
 *    CHARGEBACK CREDIT);
 *  - fee is read from the platform leg, disambiguated by description because the
 *    platform account is touched by all three movements.
 */
object SettlementExtractor {

    fun extract(baseReference: String, entries: List<LedgerEntry>): List<MovementLine> =
        listOf(
            Spec(
                reference = baseReference,
                kind = MovementKind.CAPTURE,
                grossSign = 1,
                grossLegs = entries.filter {
                    it.accountType == AccountType.INCOMING && it.entryType == EntryType.DEBIT
                },
                feeSign = 1,
                feeLegs = entries.filter {
                    it.accountType == AccountType.PLATFORM &&
                        it.description == LedgerService.DESC_PLATFORM_FEE
                }
            ),
            Spec(
                reference = "$baseReference:refund",
                kind = MovementKind.REFUND,
                grossSign = -1,
                grossLegs = entries.filter {
                    it.accountType == AccountType.OUTGOING && it.entryType == EntryType.CREDIT
                },
                feeSign = -1,
                feeLegs = entries.filter {
                    it.accountType == AccountType.PLATFORM &&
                        it.description == LedgerService.DESC_REFUND_FEE
                }
            ),
            Spec(
                reference = "$baseReference:chargeback",
                kind = MovementKind.CHARGEBACK,
                grossSign = -1,
                grossLegs = entries.filter {
                    it.accountType == AccountType.CHARGEBACK && it.entryType == EntryType.CREDIT
                },
                feeSign = 1,
                feeLegs = entries.filter {
                    it.accountType == AccountType.PLATFORM &&
                        it.entryType == EntryType.CREDIT &&
                        it.description == LedgerService.DESC_CHARGEBACK_FEE
                }
            )
        ).mapNotNull { it.toLine() }

    /** Per-kind leg selection plus the sign convention, projected to a line. */
    private data class Spec(
        val reference: String,
        val kind: MovementKind,
        val grossSign: Int,
        val grossLegs: List<LedgerEntry>,
        val feeSign: Int,
        val feeLegs: List<LedgerEntry>
    ) {
        fun toLine(): MovementLine? {
            if (grossLegs.isEmpty()) return null
            return MovementLine(
                reference = reference,
                kind = kind,
                grossMinor = grossSign * grossLegs.sumOf { it.amount },
                feeMinor = feeSign * feeLegs.sumOf { it.amount },
                currency = grossLegs.first().currency,
                occurredAt = grossLegs.maxOf { it.createdAt }
            )
        }
    }
}
