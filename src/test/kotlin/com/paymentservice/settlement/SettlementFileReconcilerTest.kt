package com.paymentservice.settlement

import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the file-reconciliation semantics shared with the Python oracle:
 * exhaustive reference partition, duplicate exclusion, window boundary, and
 * currency short-circuit. Pure, mutation tested - window boundary and sign
 * cases are here precisely because integration tests cannot backdate ledger
 * timestamps.
 */
class SettlementFileReconcilerTest {

    private val asOf = Instant.parse("2026-06-12T09:00:00Z")
    private val window = Duration.ofDays(2)
    private val cutoff = asOf.minus(window)

    private fun movement(reference: String, occurredAt: Instant = cutoff) =
        MovementLine(reference, MovementKind.CAPTURE, 10_000, 200, "EUR", occurredAt)

    private fun fileLine(
        reference: String,
        kind: MovementKind = MovementKind.CAPTURE,
        gross: Long = 10_000,
        fee: Long = 200,
        currency: String = "EUR"
    ) = AcquirerSettlementLine(reference, kind, gross, fee, currency)

    private fun reconcile(ledger: List<MovementLine>, file: List<AcquirerSettlementLine>) =
        SettlementFileReconciler.reconcile(ledger, file, asOf, window)

    @Test
    fun `identical sides reconcile clean`() {
        val result = reconcile(
            listOf(
                movement("ch1"),
                movement("ch1:refund")
                    .copy(kind = MovementKind.REFUND, grossMinor = -3_000, feeMinor = -60)
            ),
            listOf(fileLine("ch1"), fileLine("ch1:refund", MovementKind.REFUND, -3_000, -60))
        )
        assertEquals(2, result.matchedCount)
        assertTrue(result.discrepancies.isEmpty())
        assertTrue(result.pending.isEmpty())
    }

    @Test
    fun `ledger movement at or before the cutoff missing from the file is a discrepancy`() {
        val result = reconcile(listOf(movement("ch1", occurredAt = cutoff)), emptyList())
        val d = result.discrepancies.single()
        assertEquals(FileDiscrepancyType.MISSING_IN_SETTLEMENT, d.type)
        assertEquals("ch1", d.reference)
        assertEquals("10000", d.ledgerValue)
        assertTrue(result.pending.isEmpty())
    }

    @Test
    fun `ledger movement inside the window is pending, not missing`() {
        val result = reconcile(
            listOf(movement("ch1", occurredAt = cutoff.plusSeconds(1))),
            emptyList()
        )
        assertTrue(result.discrepancies.isEmpty())
        assertEquals(listOf("ch1"), result.pending)
    }

    @Test
    fun `file line unknown to the ledger is missing in ledger`() {
        val result = reconcile(emptyList(), listOf(fileLine("phantom-1", gross = 100)))
        val d = result.discrepancies.single()
        assertEquals(FileDiscrepancyType.MISSING_IN_LEDGER, d.type)
        assertEquals("phantom-1", d.reference)
        assertEquals("100", d.settlementValue)
    }

    @Test
    fun `kind mismatch carries both values`() {
        val result = reconcile(
            listOf(movement("ch1")),
            listOf(fileLine("ch1", kind = MovementKind.REFUND))
        )
        val d = result.discrepancies.single()
        assertEquals(FileDiscrepancyType.KIND_MISMATCH, d.type)
        assertEquals("CAPTURE", d.ledgerValue)
        assertEquals("REFUND", d.settlementValue)
        assertEquals(1, result.matchedCount)
    }

    @Test
    fun `currency mismatch suppresses the gross and fee comparison`() {
        val result = reconcile(
            listOf(movement("ch1")),
            listOf(fileLine("ch1", gross = 99_999, fee = 1, currency = "USD"))
        )
        val d = result.discrepancies.single()
        assertEquals(FileDiscrepancyType.CURRENCY_MISMATCH, d.type)
        assertEquals("EUR", d.ledgerValue)
        assertEquals("USD", d.settlementValue)
    }

    @Test
    fun `gross and fee mismatches are independent`() {
        val result = reconcile(
            listOf(movement("ch1")),
            listOf(fileLine("ch1", gross = 10_001, fee = 201))
        )
        assertEquals(
            listOf(FileDiscrepancyType.GROSS_MISMATCH, FileDiscrepancyType.FEE_MISMATCH),
            result.discrepancies.map { it.type }
        )
        val fee = result.discrepancies.last()
        assertEquals("200", fee.ledgerValue)
        assertEquals("201", fee.settlementValue)
    }

    @Test
    fun `duplicate file reference is flagged and excluded from matching`() {
        val result = reconcile(
            listOf(movement("ch1", occurredAt = cutoff.plusSeconds(1))),
            listOf(fileLine("ch1"), fileLine("ch1"))
        )
        assertEquals(0, result.matchedCount)
        val dup = result.discrepancies.single()
        assertEquals(FileDiscrepancyType.DUPLICATE_REFERENCE, dup.type)
        assertEquals("ch1", dup.reference)
        // the ledger side becomes ledger-only; inside the window it parks as pending
        assertEquals(listOf("ch1"), result.pending)
    }

    @Test
    fun `duplicate ledger reference is flagged and excluded from matching`() {
        val result = reconcile(
            listOf(movement("ch1"), movement("ch1"), movement("ch1")),
            listOf(fileLine("ch1", gross = 55))
        )
        assertEquals(0, result.matchedCount)
        assertEquals(
            listOf(FileDiscrepancyType.DUPLICATE_REFERENCE, FileDiscrepancyType.MISSING_IN_LEDGER),
            result.discrepancies.map { it.type }
        )
    }
}
