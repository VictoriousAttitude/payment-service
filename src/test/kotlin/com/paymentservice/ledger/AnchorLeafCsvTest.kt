package com.paymentservice.ledger

import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Pins the leaf export CSV wire contract the Python anchor verifier parses.
 * Golden rows reuse the [CanonicalLeafCodecTest] fixtures so both contracts
 * (canonical bytes and CSV columns) are pinned against the same entries.
 * Mutation tested.
 */
class AnchorLeafCsvTest {

    private val createdAt = Instant.parse("2026-01-02T03:04:05.123456Z")

    private fun paymentEntry() = LedgerEntry(
        id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        transactionId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
        postingGroupId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
        accountType = AccountType.MERCHANT,
        accountId = UUID.fromString("00000000-0000-0000-0000-000000000003"),
        entryType = EntryType.CREDIT,
        amount = 9800,
        currency = "EUR",
        description = "capture",
        createdAt = createdAt
    )

    private fun treasuryEntry() = LedgerEntry(
        id = UUID.fromString("00000000-0000-0000-0000-000000000005"),
        transactionId = null,
        postingGroupId = UUID.fromString("00000000-0000-0000-0000-000000000004"),
        accountType = AccountType.MERCHANT_PAYABLE,
        accountId = UUID.fromString("00000000-0000-0000-0000-000000000003"),
        entryType = EntryType.DEBIT,
        amount = 5000,
        currency = "USD",
        description = null,
        createdAt = createdAt
    )

    private fun leaf(index: Int, entry: LedgerEntry) =
        LedgerAnchorLeaf(epoch = 1, leafIndex = index, entryId = entry.id)

    @Test
    fun `golden csv for a payment and a treasury leaf`() {
        val rendered = AnchorLeafCsv.render(
            listOf(leaf(0, paymentEntry()) to paymentEntry(), leaf(1, treasuryEntry()) to treasuryEntry())
        )
        assertEquals(
            AnchorLeafCsv.HEADER + "\n" +
                "0,00000000-0000-0000-0000-000000000001,00000000-0000-0000-0000-000000000002," +
                "00000000-0000-0000-0000-000000000002,MERCHANT,00000000-0000-0000-0000-000000000003," +
                "CREDIT,9800,EUR,1767323045123456,Y2FwdHVyZQ==\n" +
                "1,00000000-0000-0000-0000-000000000005,,00000000-0000-0000-0000-000000000004," +
                "MERCHANT_PAYABLE,00000000-0000-0000-0000-000000000003,DEBIT,5000,USD," +
                "1767323045123456,-\n",
            rendered
        )
    }

    @Test
    fun `empty description encodes as empty base64 distinct from the null marker`() {
        val entry = LedgerEntry(
            id = treasuryEntry().id,
            transactionId = null,
            postingGroupId = treasuryEntry().postingGroupId,
            accountType = AccountType.MERCHANT_PAYABLE,
            accountId = treasuryEntry().accountId,
            entryType = EntryType.DEBIT,
            amount = 5000,
            currency = "USD",
            description = "",
            createdAt = createdAt
        )
        val emptyRow = AnchorLeafCsv.render(listOf(leaf(0, entry) to entry)).lines()[1]
        val nullRow = AnchorLeafCsv.render(listOf(leaf(0, treasuryEntry()) to treasuryEntry())).lines()[1]
        assertEquals("", emptyRow.substringAfterLast(','))
        assertEquals("-", nullRow.substringAfterLast(','))
        assertNotEquals(emptyRow, nullRow)
    }

    @Test
    fun `no leaves renders the header alone`() {
        assertEquals(AnchorLeafCsv.HEADER + "\n", AnchorLeafCsv.render(emptyList()))
    }
}
