package com.paymentservice.ledger

import java.util.Base64

/**
 * Leaf export wire contract for the Python anchor verifier. The verifier
 * rebuilds each [CanonicalLeafCodec] byte string from these columns and
 * recomputes root and chain independently, so this row format is as much a
 * contract as the canonical encoding itself.
 *
 * The description is free text inside a strict no-quoting CSV, so it travels
 * base64-encoded; "-" marks a null description, which must stay distinct from
 * the empty string ("" encodes to an empty base64 field). A null transaction
 * id is an empty field, mirroring the canonical encoding.
 */
object AnchorLeafCsv {

    const val HEADER = "leaf_index,entry_id,transaction_id,posting_group_id,account_type," +
        "account_id,entry_type,amount,currency,created_at_micros,description_b64"

    private const val NULL_DESCRIPTION = "-"

    fun render(rows: List<Pair<LedgerAnchorLeaf, LedgerEntry>>): String =
        (listOf(HEADER) + rows.map { (leaf, entry) -> row(leaf, entry) })
            .joinToString("\n", postfix = "\n")

    private fun row(leaf: LedgerAnchorLeaf, entry: LedgerEntry): String {
        val description = entry.description
            ?.let { Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }
            ?: NULL_DESCRIPTION
        return "${leaf.leafIndex},${entry.id},${entry.transactionId ?: ""},${entry.postingGroupId}," +
            "${entry.accountType},${entry.accountId},${entry.entryType},${entry.amount},${entry.currency}," +
            "${CanonicalLeafCodec.epochMicros(entry.createdAt)},$description"
    }
}
