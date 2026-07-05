package com.paymentservice.ledger

import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Pins the cross-language canonical leaf contract byte for byte. The golden
 * strings and hashes here are duplicated verbatim in the Python anchor
 * verifier test suite: if either side drifts, its golden test fails before
 * any anchor does. Mutation tested.
 */
class CanonicalLeafCodecTest {

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

    @Test
    fun `golden canonical string for a payment entry`() {
        assertEquals(
            "00000000-0000-0000-0000-000000000001|00000000-0000-0000-0000-000000000002|" +
                "00000000-0000-0000-0000-000000000002|MERCHANT|00000000-0000-0000-0000-000000000003|" +
                "CREDIT|9800|EUR|1767323045123456|1capture",
            CanonicalLeafCodec.canonicalString(paymentEntry())
        )
    }

    @Test
    fun `golden canonical string for a treasury entry with null transaction and description`() {
        assertEquals(
            "00000000-0000-0000-0000-000000000005||00000000-0000-0000-0000-000000000004|" +
                "MERCHANT_PAYABLE|00000000-0000-0000-0000-000000000003|DEBIT|5000|USD|" +
                "1767323045123456|0",
            CanonicalLeafCodec.canonicalString(treasuryEntry())
        )
    }

    @Test
    fun `golden leaf hashes and epoch root shared with the python verifier`() {
        val payment = CanonicalLeafCodec.encode(paymentEntry())
        val treasury = CanonicalLeafCodec.encode(treasuryEntry())
        assertEquals(
            "f8ef1653776f813dce4f2aa8ea0d4e684abebbc8122b643c03b21960078b894e",
            MerkleTree.toHex(MerkleTree.leafHash(payment))
        )
        assertEquals(
            "9e722ceb7e380146739b5b23e66daf0475656fe82bae3a374aa5be1f4b1adf6a",
            MerkleTree.toHex(MerkleTree.leafHash(treasury))
        )
        assertEquals(
            "3dc4a3e169aba383f31aa41cff239a715cb04c7f5fa114f740e9581fefbad646",
            MerkleTree.rootHex(listOf(payment, treasury))
        )
    }

    @Test
    fun `null description and empty description encode differently`() {
        val empty = LedgerEntry(
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
        assertEquals("1", CanonicalLeafCodec.canonicalString(empty).substringAfterLast('|'))
        assertEquals("0", CanonicalLeafCodec.canonicalString(treasuryEntry()).substringAfterLast('|'))
        assertNotEquals(
            CanonicalLeafCodec.canonicalString(treasuryEntry()),
            CanonicalLeafCodec.canonicalString(empty)
        )
    }

    @Test
    fun `description containing pipes stays injective because it is the last field`() {
        val tricky = LedgerEntry(
            id = paymentEntry().id,
            transactionId = paymentEntry().transactionId,
            postingGroupId = paymentEntry().postingGroupId,
            accountType = AccountType.MERCHANT,
            accountId = paymentEntry().accountId,
            entryType = EntryType.CREDIT,
            amount = 9800,
            currency = "EUR",
            description = "a|b|c",
            createdAt = createdAt
        )
        assertEquals("1a|b|c", CanonicalLeafCodec.canonicalString(tricky).substringAfter("|1767323045123456|"))
        assertNotEquals(
            CanonicalLeafCodec.canonicalString(paymentEntry()),
            CanonicalLeafCodec.canonicalString(tricky)
        )
    }

    @Test
    fun `epoch micros truncates nanosecond precision to what postgres stores`() {
        assertEquals(1_767_323_045_123_456L, CanonicalLeafCodec.epochMicros(createdAt))
        assertEquals(
            1_767_323_045_123_456L,
            CanonicalLeafCodec.epochMicros(Instant.parse("2026-01-02T03:04:05.123456789Z"))
        )
        assertEquals(0L, CanonicalLeafCodec.epochMicros(Instant.EPOCH))
    }
}
