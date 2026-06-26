package com.paymentservice.settlement

import com.paymentservice.ledger.AccountType
import com.paymentservice.ledger.EntryType
import com.paymentservice.ledger.LedgerEntry
import com.paymentservice.ledger.LedgerService
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the ledger -> settlement movement projection: sign convention, fee
 * attribution by description, per-kind aggregation and the reference scheme.
 * Pure, mutation tested.
 */
class SettlementExtractorTest {

    private val txnId = UUID.randomUUID()
    private val merchantId = UUID.randomUUID()
    private val t0 = Instant.parse("2026-06-10T09:00:00Z")

    private fun entry(
        accountType: AccountType,
        entryType: EntryType,
        amount: Long,
        description: String?,
        createdAt: Instant = t0
    ) = LedgerEntry(
        transactionId = txnId,
        accountType = accountType,
        accountId = if (accountType == AccountType.MERCHANT) merchantId else txnId,
        entryType = entryType,
        amount = amount,
        currency = "EUR",
        description = description,
        createdAt = createdAt
    )

    private fun captureLegs(amount: Long, fee: Long, at: Instant = t0) = listOf(
        entry(AccountType.INCOMING, EntryType.DEBIT, amount, "Payment captured", at),
        entry(AccountType.MERCHANT, EntryType.CREDIT, amount - fee, "Merchant payout (net of fee)", at),
        entry(AccountType.PLATFORM, EntryType.CREDIT, fee, LedgerService.DESC_PLATFORM_FEE, at)
    )

    private fun refundLegs(amount: Long, fee: Long, at: Instant = t0) = listOf(
        entry(AccountType.MERCHANT, EntryType.DEBIT, amount - fee, "Refund: debit merchant", at),
        entry(AccountType.PLATFORM, EntryType.DEBIT, fee, LedgerService.DESC_REFUND_FEE, at),
        entry(AccountType.OUTGOING, EntryType.CREDIT, amount, "Refund issued", at)
    )

    private fun chargebackLegs(amount: Long, fee: Long, at: Instant = t0) = listOf(
        entry(AccountType.MERCHANT, EntryType.DEBIT, amount, "Chargeback: funds reversed", at),
        entry(AccountType.CHARGEBACK, EntryType.CREDIT, amount, "Chargeback: returned to cardholder", at),
        entry(AccountType.MERCHANT, EntryType.DEBIT, fee, LedgerService.DESC_CHARGEBACK_FEE, at),
        entry(AccountType.PLATFORM, EntryType.CREDIT, fee, LedgerService.DESC_CHARGEBACK_FEE, at)
    )

    @Test
    fun `capture projects to a positive gross and positive fee on the base reference`() {
        val lines = SettlementExtractor.extract("ref_1", captureLegs(10_000, 200))
        assertEquals(1, lines.size)
        val line = lines.single()
        assertEquals("ref_1", line.reference)
        assertEquals(MovementKind.CAPTURE, line.kind)
        assertEquals(10_000, line.grossMinor)
        assertEquals(200, line.feeMinor)
        assertEquals("EUR", line.currency)
        assertEquals(t0, line.occurredAt)
    }

    @Test
    fun `refund projects to a negative gross and a returned negative fee`() {
        val lines = SettlementExtractor.extract("ref_1", refundLegs(3_000, 60))
        val line = lines.single()
        assertEquals("ref_1:refund", line.reference)
        assertEquals(MovementKind.REFUND, line.kind)
        assertEquals(-3_000, line.grossMinor)
        assertEquals(-60, line.feeMinor)
    }

    @Test
    fun `chargeback gross is negative and fee reads the platform leg only, not the merchant clawback`() {
        val lines = SettlementExtractor.extract("ref_1", chargebackLegs(10_000, 1_500))
        val line = lines.single()
        assertEquals("ref_1:chargeback", line.reference)
        assertEquals(MovementKind.CHARGEBACK, line.kind)
        assertEquals(-10_000, line.grossMinor)
        // both the merchant debit and the platform credit carry DESC_CHARGEBACK_FEE;
        // only the platform credit must count, so the fee is 1500 not 3000.
        assertEquals(1_500, line.feeMinor)
    }

    @Test
    fun `multi-capture and partial refund on one transaction aggregate per kind`() {
        val entries = captureLegs(6_000, 120, t0) +
            captureLegs(4_000, 80, t0.plusSeconds(60)) +
            refundLegs(3_000, 60, t0.plusSeconds(120))

        val lines = SettlementExtractor.extract("ref_1", entries)
        assertEquals(2, lines.size)

        val capture = lines.first { it.kind == MovementKind.CAPTURE }
        assertEquals(10_000, capture.grossMinor) // 6000 + 4000
        assertEquals(200, capture.feeMinor)      // 120 + 80
        assertEquals(t0.plusSeconds(60), capture.occurredAt) // max of the gross legs

        val refund = lines.first { it.kind == MovementKind.REFUND }
        assertEquals(-3_000, refund.grossMinor)
    }

    @Test
    fun `a transaction with no movements of a kind emits no line for it`() {
        val lines = SettlementExtractor.extract("ref_1", captureLegs(10_000, 200))
        assertTrue(lines.none { it.kind == MovementKind.REFUND })
        assertTrue(lines.none { it.kind == MovementKind.CHARGEBACK })
        assertTrue(SettlementExtractor.extract("ref_1", emptyList()).isEmpty())
    }
}
