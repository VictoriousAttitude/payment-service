package com.paymentservice.ledger

import java.time.Instant

/**
 * Canonical byte encoding of a [LedgerEntry] for Merkle anchoring.
 *
 * This is a cross-language wire contract: the Python anchor verifier rebuilds
 * the exact same bytes from the exported leaf CSV, so any change here is a
 * breaking change that invalidates every previously written anchor.
 *
 * Format (UTF-8, pipe-delimited, description last):
 *
 *   id|transactionId|postingGroupId|accountType|accountId|entryType|amount|currency|createdAtMicros|D
 *
 * Injectivity argument: the first nine fields draw from restricted alphabets
 * (lowercase hyphenated UUIDs, enum names, decimal integers, ISO 4217 codes)
 * that can never contain '|', so the first nine delimiters are unambiguous.
 * The free-text description is the entire remainder and needs no escaping.
 * Nullable fields encode null distinctly: a null transactionId is the empty
 * string (a UUID is never empty) and D is "0" for a null description or
 * "1" + text otherwise (so null and empty string cannot collide).
 *
 * Timestamps are epoch microseconds because PostgreSQL timestamptz stores
 * microsecond precision: hashing must happen over what the database durably
 * holds, not over the nanosecond Instant an in-memory entity may carry.
 */
object CanonicalLeafCodec {

    private const val MICROS_PER_SECOND = 1_000_000L
    private const val NANOS_PER_MICRO = 1_000L
    private const val NULL_MARKER = "0"
    private const val PRESENT_MARKER = "1"

    fun canonicalString(entry: LedgerEntry): String {
        val transactionId = entry.transactionId?.toString().orEmpty()
        val description = entry.description?.let { PRESENT_MARKER + it } ?: NULL_MARKER
        return "${entry.id}|$transactionId|${entry.postingGroupId}|${entry.accountType}|" +
            "${entry.accountId}|${entry.entryType}|${entry.amount}|${entry.currency}|" +
            "${epochMicros(entry.createdAt)}|$description"
    }

    fun encode(entry: LedgerEntry): ByteArray = canonicalString(entry).toByteArray(Charsets.UTF_8)

    fun epochMicros(instant: Instant): Long =
        instant.epochSecond * MICROS_PER_SECOND + instant.nano / NANOS_PER_MICRO
}
